/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.sql.avatica;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import org.apache.calcite.avatica.AvaticaSeverity;
import org.apache.calcite.avatica.MetaImpl;
import org.apache.calcite.avatica.MissingResultsException;
import org.apache.calcite.avatica.NoSuchConnectionException;
import org.apache.calcite.avatica.NoSuchStatementException;
import org.apache.calcite.avatica.QueryState;
import org.apache.calcite.avatica.remote.AvaticaRuntimeException;
import org.apache.calcite.avatica.remote.Service.ErrorResponse;
import org.apache.calcite.avatica.remote.TypedValue;
import org.apache.druid.guice.LazySingleton;
import org.apache.druid.guice.annotations.NativeQuery;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.UOE;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.QueryContext;
import org.apache.druid.server.security.AuthenticationResult;
import org.apache.druid.server.security.Authenticator;
import org.apache.druid.server.security.AuthenticatorMapper;
import org.apache.druid.server.security.ForbiddenException;
import org.apache.druid.sql.SqlQueryPlus;
import org.apache.druid.sql.SqlStatementFactory;
import org.apache.druid.sql.calcite.planner.Calcites;
import org.apache.druid.sql.calcite.planner.PlannerContext;
import org.joda.time.Interval;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@LazySingleton
public class DruidMeta extends MetaImpl
{
  /**
   * Logs any throwable and string format message with args at the error level.
   *
   * @param error   the Throwable to be logged
   * @param message the message to be logged. Can be in string format structure
   * @param format  the format arguments for the format message string
   * @param <T>     any type that extends throwable
   * @return the original Throwable
   */
  public static <T extends Throwable> T logFailure(T error, String message, Object... format)
  {
    LOG.error(error, message, format);
    return error;
  }

  /**
   * Logs any throwable at the error level with the throwables message.
   *
   * @param error the throwable to be logged
   * @param <T>   any type that extends throwable
   * @return the original Throwable
   */
  public static <T extends Throwable> T logFailure(T error)
  {
    logFailure(error, error.getMessage());
    return error;
  }

  private static final Logger LOG = new Logger(DruidMeta.class);
  private static final Set<String> SENSITIVE_CONTEXT_FIELDS = ImmutableSet.of(
      "user", "password"
  );

  private final SqlStatementFactory sqlStatementFactory;
  private final ScheduledExecutorService exec;
  private final AvaticaServerConfig config;
  private final List<Authenticator> authenticators;
  private final ErrorHandler errorHandler;

  /**
   * Tracks logical connections.
   */
  private final ConcurrentMap<String, DruidConnection> connections = new ConcurrentHashMap<>();

  /**
   * Number of connections reserved in "connections". May be higher than the actual number of connections at times,
   * such as when we're reserving space to open a new one.
   */
  private final AtomicInteger connectionCount = new AtomicInteger();

  @Inject
  public DruidMeta(
      final @NativeQuery SqlStatementFactory sqlStatementFactory,
      final AvaticaServerConfig config,
      final ErrorHandler errorHandler,
      final AuthenticatorMapper authMapper
  )
  {
    this(
        sqlStatementFactory,
        config,
        errorHandler,
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat("DruidMeta-ScheduledExecutor-%d")
                .setDaemon(true)
                .build()
        ),
        authMapper.getAuthenticatorChain()
    );
  }

  public DruidMeta(
      final SqlStatementFactory sqlStatementFactory,
      final AvaticaServerConfig config,
      final ErrorHandler errorHandler,
      final ScheduledExecutorService exec,
      final List<Authenticator> authenticators
  )
  {
    super(null);
    this.sqlStatementFactory = sqlStatementFactory;
    this.config = config;
    this.errorHandler = errorHandler;
    this.exec = exec;
    this.authenticators = authenticators;
  }

  @Override
  public void openConnection(final ConnectionHandle ch, final Map<String, String> info)
  {
    try {
      // Build connection context.
      final Map<String, Object> secret = new HashMap<>();
      final Map<String, Object> contextMap = new HashMap<>();
      if (info != null) {
        for (Map.Entry<String, String> entry : info.entrySet()) {
          if (SENSITIVE_CONTEXT_FIELDS.contains(entry.getKey())) {
            secret.put(entry.getKey(), entry.getValue());
          } else {
            contextMap.put(entry.getKey(), entry.getValue());
          }
        }
      }
      // we don't want to stringify arrays for JDBC ever because Avatica needs to handle this
      final QueryContext context = new QueryContext(contextMap);
      context.addSystemParam(PlannerContext.CTX_SQL_STRINGIFY_ARRAYS, false);
      openDruidConnection(ch.id, secret, context);
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      // we want to avoid sanitizing Avatica specific exceptions as the Avatica code can rely on them to handle issues
      // differently
      throw mapException(t);
    }
  }

  @Override
  public void closeConnection(final ConnectionHandle ch)
  {
    try {
      final DruidConnection druidConnection = connections.remove(ch.id);
      if (druidConnection != null) {
        connectionCount.decrementAndGet();
        druidConnection.close();
      }
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  @Override
  public ConnectionProperties connectionSync(final ConnectionHandle ch, final ConnectionProperties connProps)
  {
    try {
      // getDruidConnection re-syncs it.
      getDruidConnection(ch.id);
      return connProps;
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  /**
   * Creates a new implementation of the one-pass JDBC {@code Statement}
   * class. Corresponds to the JDBC {@code Connection.createStatement()}
   * method.
   */
  @Override
  public StatementHandle createStatement(final ConnectionHandle ch)
  {
    try {
      final DruidJdbcStatement druidStatement = getDruidConnection(ch.id).createStatement(sqlStatementFactory);
      return new StatementHandle(ch.id, druidStatement.getStatementId(), null);
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  /**
   * Creates a new implementation of the JDBC {@code PreparedStatement}
   * class which allows preparing once, executing many times. Corresponds to
   * the JDBC {@code Connection.prepareStatement()} call.
   */
  @Override
  public StatementHandle prepare(
      final ConnectionHandle ch,
      final String sql,
      final long maxRowCount
  )
  {
    try {
      final DruidConnection druidConnection = getDruidConnection(ch.id);
      SqlQueryPlus sqlReq = new SqlQueryPlus(
          sql,
          null, // Context provided by connection
          null, // No parameters in this path
          doAuthenticate(druidConnection)
      );
      DruidJdbcPreparedStatement stmt = getDruidConnection(ch.id).createPreparedStatement(
          sqlStatementFactory,
          sqlReq,
          maxRowCount);
      stmt.prepare();
      LOG.debug("Successfully prepared statement [%s] for execution", stmt.getStatementId());
      return new StatementHandle(ch.id, stmt.getStatementId(), stmt.getSignature());
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  private AuthenticationResult doAuthenticate(final DruidConnection druidConnection)
  {
    AuthenticationResult authenticationResult = authenticateConnection(druidConnection);
    if (authenticationResult == null) {
      throw logFailure(
          new ForbiddenException("Authentication failed."),
          "Authentication failed for prepare"
      );
    }
    return authenticationResult;
  }

  @Deprecated
  @Override
  public ExecuteResult prepareAndExecute(
      final StatementHandle h,
      final String sql,
      final long maxRowCount,
      final PrepareCallback callback
  )
  {
    // Avatica doesn't call this.
    throw errorHandler.sanitize(new UOE("Deprecated"));
  }

  /**
   * Prepares and executes a JDBC {@code Statement}
   */
  @Override
  public ExecuteResult prepareAndExecute(
      final StatementHandle statement,
      final String sql,
      final long maxRowCount,
      final int maxRowsInFirstFrame,
      final PrepareCallback callback
  ) throws NoSuchStatementException
  {

    try {
      // Ignore "callback", this class is designed for use with LocalService which doesn't use it.
      final DruidJdbcStatement druidStatement = getDruidStatement(statement, DruidJdbcStatement.class);
      final DruidConnection druidConnection = getDruidConnection(statement.connectionId);
      AuthenticationResult authenticationResult = doAuthenticate(druidConnection);
      SqlQueryPlus sqlRequest = SqlQueryPlus.builder(sql)
          .auth(authenticationResult)
          .build();
      druidStatement.execute(sqlRequest, maxRowCount);
      ExecuteResult result = doFetch(druidStatement, maxRowsInFirstFrame);
      LOG.debug("Successfully prepared statement [%s] and started execution", druidStatement.getStatementId());
      return result;
    }
    // Cannot affect these exceptions as Avatica handles them.
    catch (NoSuchConnectionException | NoSuchStatementException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  /**
   * Convert a Druid exception to an Avatica exception. Avatica can pass
   * along things like an error code and SQL state. There are defined
   * values for security failures, so map to those.
   */
  private RuntimeException mapException(Throwable t)
  {
    // BasicSecurityAuthenticationException is not visible here.
    String className = t.getClass().getSimpleName();
    if (t instanceof ForbiddenException ||
        "BasicSecurityAuthenticationException".equals(className)) {
      throw new AvaticaRuntimeException(
          t.getMessage(),
          ErrorResponse.UNAUTHORIZED_ERROR_CODE,
          ErrorResponse.UNAUTHORIZED_SQL_STATE,
          AvaticaSeverity.ERROR);
    }

    // Let Avatica do its default mapping.
    throw errorHandler.sanitize(t);
  }

  private ExecuteResult doFetch(AbstractDruidJdbcStatement druidStatement, int maxRows)
  {
    final Signature signature = druidStatement.getSignature();
    final Frame firstFrame = druidStatement.nextFrame(
                                       AbstractDruidJdbcStatement.START_OFFSET,
                                       getEffectiveMaxRowsPerFrame(maxRows)
                                   );

    return new ExecuteResult(
        ImmutableList.of(
            MetaResultSet.create(
                druidStatement.connectionId,
                druidStatement.statementId,
                false,
                signature,
                firstFrame
            )
        )
    );
  }

  @Override
  public ExecuteBatchResult prepareAndExecuteBatch(
      final StatementHandle statement,
      final List<String> sqlCommands
  )
  {
    // Batch statements are used for bulk updates, but we don't support updates.
    throw errorHandler.sanitize(new UOE("Batch statements not supported"));
  }

  @Override
  public ExecuteBatchResult executeBatch(
      final StatementHandle statement,
      final List<List<TypedValue>> parameterValues
  )
  {
    // Batch statements are used for bulk updates, but we don't support updates.
    throw errorHandler.sanitize(new UOE("Batch statements not supported"));
  }

  @Override
  public Frame fetch(
      final StatementHandle statement,
      final long offset,
      final int fetchMaxRowCount
  ) throws NoSuchStatementException, MissingResultsException
  {
    try {
      final int maxRows = getEffectiveMaxRowsPerFrame(fetchMaxRowCount);
      LOG.debug("Fetching next frame from offset[%s] with [%s] rows for statement[%s]", offset, maxRows, statement.id);
      return getDruidStatement(statement, AbstractDruidJdbcStatement.class).nextFrame(offset, maxRows);
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  @Deprecated
  @Override
  public ExecuteResult execute(
      final StatementHandle statement,
      final List<TypedValue> parameterValues,
      final long maxRowCount
  )
  {
    // Avatica doesn't call this.
    throw errorHandler.sanitize(new UOE("Deprecated"));
  }

  @Override
  public ExecuteResult execute(
      final StatementHandle statement,
      final List<TypedValue> parameterValues,
      final int maxRowsInFirstFrame
  ) throws NoSuchStatementException
  {
    try {
      final DruidJdbcPreparedStatement druidStatement =
          getDruidStatement(statement, DruidJdbcPreparedStatement.class);
      druidStatement.execute(parameterValues);
      ExecuteResult result = doFetch(druidStatement, maxRowsInFirstFrame);
      LOG.debug(
          "Successfully started execution of statement[%s]",
          druidStatement.getStatementId());
      return result;
    }
    catch (NoSuchStatementException | NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  @Override
  public Iterable<Object> createIterable(
      final StatementHandle statement,
      final QueryState state,
      final Signature signature,
      final List<TypedValue> parameterValues,
      final Frame firstFrame
  )
  {
    // Avatica calls this but ignores the return value.
    return null;
  }

  @Override
  public void closeStatement(final StatementHandle h)
  {
    try {
      // connections.get, not getDruidConnection, since we want to silently ignore nonexistent statements
      final DruidConnection druidConnection = connections.get(h.connectionId);
      if (druidConnection != null) {
        druidConnection.closeStatement(h.id);
      }
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  @Override
  public boolean syncResults(
      final StatementHandle sh,
      final QueryState state,
      final long offset
  ) throws NoSuchStatementException
  {
    try {
      final AbstractDruidJdbcStatement druidStatement = getDruidStatement(sh, AbstractDruidJdbcStatement.class);
      final boolean isDone = druidStatement.isDone();
      final long currentOffset = druidStatement.getCurrentOffset();
      if (currentOffset != offset) {
        throw logFailure(new ISE(
            "Requested offset[%,d] does not match currentOffset[%,d]",
            offset,
            currentOffset
        ));
      }
      return !isDone;
    }
    catch (NoSuchStatementException | NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  @Override
  public void commit(final ConnectionHandle ch)
  {
    // We don't support writes, just ignore commits.
  }

  @Override
  public void rollback(final ConnectionHandle ch)
  {
    // We don't support writes, just ignore rollbacks.
  }

  @Override
  public Map<DatabaseProperty, Object> getDatabaseProperties(final ConnectionHandle ch)
  {
    return ImmutableMap.of();
  }

  @Override
  public MetaResultSet getCatalogs(final ConnectionHandle ch)
  {
    try {
      final String sql = "SELECT\n"
                         + "  DISTINCT CATALOG_NAME AS TABLE_CAT\n"
                         + "FROM\n"
                         + "  INFORMATION_SCHEMA.SCHEMATA\n"
                         + "ORDER BY\n"
                         + "  TABLE_CAT\n";

      return sqlResultSet(ch, sql);
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  @Override
  public MetaResultSet getSchemas(
      final ConnectionHandle ch,
      final String catalog,
      final Pat schemaPattern
  )
  {
    try {
      final List<String> whereBuilder = new ArrayList<>();
      if (catalog != null) {
        whereBuilder.add("SCHEMATA.CATALOG_NAME = " + Calcites.escapeStringLiteral(catalog));
      }

      if (schemaPattern.s != null) {
        whereBuilder.add("SCHEMATA.SCHEMA_NAME LIKE " + withEscapeClause(schemaPattern.s));
      }

      final String where = whereBuilder.isEmpty() ? "" : "WHERE " + Joiner.on(" AND ").join(whereBuilder);
      final String sql = "SELECT\n"
                         + "  SCHEMA_NAME AS TABLE_SCHEM,\n"
                         + "  CATALOG_NAME AS TABLE_CATALOG\n"
                         + "FROM\n"
                         + "  INFORMATION_SCHEMA.SCHEMATA\n"
                         + where + "\n"
                         + "ORDER BY\n"
                         + "  TABLE_CATALOG, TABLE_SCHEM\n";

      return sqlResultSet(ch, sql);
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  @Override
  public MetaResultSet getTables(
      final ConnectionHandle ch,
      final String catalog,
      final Pat schemaPattern,
      final Pat tableNamePattern,
      final List<String> typeList
  )
  {
    try {
      final List<String> whereBuilder = new ArrayList<>();
      if (catalog != null) {
        whereBuilder.add("TABLES.TABLE_CATALOG = " + Calcites.escapeStringLiteral(catalog));
      }

      if (schemaPattern.s != null) {
        whereBuilder.add("TABLES.TABLE_SCHEMA LIKE " + withEscapeClause(schemaPattern.s));
      }

      if (tableNamePattern.s != null) {
        whereBuilder.add("TABLES.TABLE_NAME LIKE " + withEscapeClause(tableNamePattern.s));
      }

      if (typeList != null) {
        final List<String> escapedTypes = new ArrayList<>();
        for (String type : typeList) {
          escapedTypes.add(Calcites.escapeStringLiteral(type));
        }
        whereBuilder.add("TABLES.TABLE_TYPE IN (" + Joiner.on(", ").join(escapedTypes) + ")");
      }

      final String where = whereBuilder.isEmpty() ? "" : "WHERE " + Joiner.on(" AND ").join(whereBuilder);
      final String sql = "SELECT\n"
                         + "  TABLE_CATALOG AS TABLE_CAT,\n"
                         + "  TABLE_SCHEMA AS TABLE_SCHEM,\n"
                         + "  TABLE_NAME AS TABLE_NAME,\n"
                         + "  TABLE_TYPE AS TABLE_TYPE,\n"
                         + "  CAST(NULL AS VARCHAR) AS REMARKS,\n"
                         + "  CAST(NULL AS VARCHAR) AS TYPE_CAT,\n"
                         + "  CAST(NULL AS VARCHAR) AS TYPE_SCHEM,\n"
                         + "  CAST(NULL AS VARCHAR) AS TYPE_NAME,\n"
                         + "  CAST(NULL AS VARCHAR) AS SELF_REFERENCING_COL_NAME,\n"
                         + "  CAST(NULL AS VARCHAR) AS REF_GENERATION\n"
                         + "FROM\n"
                         + "  INFORMATION_SCHEMA.TABLES\n"
                         + where + "\n"
                         + "ORDER BY\n"
                         + "  TABLE_TYPE, TABLE_CAT, TABLE_SCHEM, TABLE_NAME\n";

      return sqlResultSet(ch, sql);
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  @Override
  public MetaResultSet getColumns(
      final ConnectionHandle ch,
      final String catalog,
      final Pat schemaPattern,
      final Pat tableNamePattern,
      final Pat columnNamePattern
  )
  {
    try {
      final List<String> whereBuilder = new ArrayList<>();
      if (catalog != null) {
        whereBuilder.add("COLUMNS.TABLE_CATALOG = " + Calcites.escapeStringLiteral(catalog));
      }

      if (schemaPattern.s != null) {
        whereBuilder.add("COLUMNS.TABLE_SCHEMA LIKE " + withEscapeClause(schemaPattern.s));
      }

      if (tableNamePattern.s != null) {
        whereBuilder.add("COLUMNS.TABLE_NAME LIKE " + withEscapeClause(tableNamePattern.s));
      }

      if (columnNamePattern.s != null) {
        whereBuilder.add("COLUMNS.COLUMN_NAME LIKE "
                         + withEscapeClause(columnNamePattern.s));
      }

      final String where = whereBuilder.isEmpty() ? "" : "WHERE " + Joiner.on(" AND ").join(whereBuilder);
      final String sql = "SELECT\n"
                         + "  TABLE_CATALOG AS TABLE_CAT,\n"
                         + "  TABLE_SCHEMA AS TABLE_SCHEM,\n"
                         + "  TABLE_NAME AS TABLE_NAME,\n"
                         + "  COLUMN_NAME AS COLUMN_NAME,\n"
                         + "  CAST(JDBC_TYPE AS INTEGER) AS DATA_TYPE,\n"
                         + "  DATA_TYPE AS TYPE_NAME,\n"
                         + "  -1 AS COLUMN_SIZE,\n"
                         + "  -1 AS BUFFER_LENGTH,\n"
                         + "  -1 AS DECIMAL_DIGITS,\n"
                         + "  -1 AS NUM_PREC_RADIX,\n"
                         + "  CASE IS_NULLABLE WHEN 'YES' THEN 1 ELSE 0 END AS NULLABLE,\n"
                         + "  CAST(NULL AS VARCHAR) AS REMARKS,\n"
                         + "  COLUMN_DEFAULT AS COLUMN_DEF,\n"
                         + "  -1 AS SQL_DATA_TYPE,\n"
                         + "  -1 AS SQL_DATETIME_SUB,\n"
                         + "  -1 AS CHAR_OCTET_LENGTH,\n"
                         + "  CAST(ORDINAL_POSITION AS INTEGER) AS ORDINAL_POSITION,\n"
                         + "  IS_NULLABLE AS IS_NULLABLE,\n"
                         + "  CAST(NULL AS VARCHAR) AS SCOPE_CATALOG,\n"
                         + "  CAST(NULL AS VARCHAR) AS SCOPE_SCHEMA,\n"
                         + "  CAST(NULL AS VARCHAR) AS SCOPE_TABLE,\n"
                         + "  -1 AS SOURCE_DATA_TYPE,\n"
                         + "  'NO' AS IS_AUTOINCREMENT,\n"
                         + "  'NO' AS IS_GENERATEDCOLUMN\n"
                         + "FROM\n"
                         + "  INFORMATION_SCHEMA.COLUMNS\n"
                         + where + "\n"
                         + "ORDER BY\n"
                         + "  TABLE_CAT, TABLE_SCHEM, TABLE_NAME, ORDINAL_POSITION\n";

      return sqlResultSet(ch, sql);
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  @Override
  public MetaResultSet getTableTypes(final ConnectionHandle ch)
  {
    try {
      final String sql = "SELECT\n"
                         + "  DISTINCT TABLE_TYPE AS TABLE_TYPE\n"
                         + "FROM\n"
                         + "  INFORMATION_SCHEMA.TABLES\n"
                         + "ORDER BY\n"
                         + "  TABLE_TYPE\n";

      return sqlResultSet(ch, sql);
    }
    catch (NoSuchConnectionException e) {
      throw e;
    }
    catch (Throwable t) {
      throw mapException(t);
    }
  }

  @VisibleForTesting
  void closeAllConnections()
  {
    for (String connectionId : ImmutableSet.copyOf(connections.keySet())) {
      closeConnection(new ConnectionHandle(connectionId));
    }
  }

  @Nullable
  private AuthenticationResult authenticateConnection(final DruidConnection connection)
  {
    Map<String, Object> context = connection.userSecret();
    for (Authenticator authenticator : authenticators) {
      LOG.debug("Attempting authentication with authenticator[%s]", authenticator.getClass());
      AuthenticationResult authenticationResult = authenticator.authenticateJDBCContext(context);
      if (authenticationResult != null) {
        LOG.debug(
            "Authenticated identity[%s] for connection[%s]",
            authenticationResult.getIdentity(),
            connection.getConnectionId()
        );
        return authenticationResult;
      }
    }
    LOG.debug("No successful authentication");
    return null;
  }

  private DruidConnection openDruidConnection(
      final String connectionId,
      final Map<String, Object> userSecret,
      final QueryContext context
  )
  {
    if (connectionCount.incrementAndGet() > config.getMaxConnections()) {
      // O(connections) but we don't expect this to happen often (it's a last-ditch effort to clear out
      // abandoned connections) or to have too many connections.
      final Iterator<Map.Entry<String, DruidConnection>> entryIterator = connections.entrySet().iterator();
      while (entryIterator.hasNext()) {
        final Map.Entry<String, DruidConnection> entry = entryIterator.next();
        if (entry.getValue().closeIfEmpty()) {
          entryIterator.remove();

          // Removed a connection, decrement the counter.
          connectionCount.decrementAndGet();
          break;
        }
      }

      if (connectionCount.get() > config.getMaxConnections()) {
        // We aren't going to make a connection after all.
        connectionCount.decrementAndGet();
        throw logFailure(
            new ISE("Too many connections"),
            "Too many connections, limit is[%,d] per broker",
            config.getMaxConnections()
        );
      }
    }

    final DruidConnection putResult = connections.putIfAbsent(
        connectionId,
        new DruidConnection(connectionId, config.getMaxStatementsPerConnection(), userSecret, context)
    );

    if (putResult != null) {
      // Didn't actually insert the connection.
      connectionCount.decrementAndGet();
      throw logFailure(new ISE("Connection[%s] already open.", connectionId));
    }

    LOG.debug("Connection[%s] opened.", connectionId);

    // Call getDruidConnection to start the timeout timer.
    return getDruidConnection(connectionId);
  }

  /**
   * Get a connection, or throw an exception if it doesn't exist. Also refreshes the timeout timer.
   *
   * @param connectionId connection id
   * @return the connection
   * @throws NoSuchConnectionException if the connection id doesn't exist
   */
  @Nonnull
  private DruidConnection getDruidConnection(final String connectionId)
  {
    final DruidConnection connection = connections.get(connectionId);

    if (connection == null) {
      throw logFailure(new NoSuchConnectionException(connectionId));
    }

    return connection.sync(
        exec.schedule(
            () -> {
              LOG.debug("Connection[%s] timed out.", connectionId);
              closeConnection(new ConnectionHandle(connectionId));
            },
            new Interval(DateTimes.nowUtc(), config.getConnectionIdleTimeout()).toDurationMillis(),
            TimeUnit.MILLISECONDS
        )
    );
  }

  @Nonnull
  private <T extends AbstractDruidJdbcStatement> T getDruidStatement(
      final StatementHandle statement,
      final Class<T> stmtClass
  ) throws NoSuchStatementException
  {
    final DruidConnection connection = getDruidConnection(statement.connectionId);
    final AbstractDruidJdbcStatement druidStatement = connection.getStatement(statement.id);
    if (druidStatement == null) {
      throw logFailure(new NoSuchStatementException(statement));
    }
    try {
      return stmtClass.cast(druidStatement);
    }
    catch (ClassCastException e) {
      throw logFailure(new NoSuchStatementException(statement));
    }
  }

  private MetaResultSet sqlResultSet(final ConnectionHandle ch, final String sql)
  {
    final StatementHandle statement = createStatement(ch);
    try {
      final ExecuteResult result = prepareAndExecute(statement, sql, -1, -1, null);
      final MetaResultSet metaResultSet = Iterables.getOnlyElement(result.resultSets);
      if (!metaResultSet.firstFrame.done) {
        throw new ISE("Expected all results to be in a single frame!");
      }
      return metaResultSet;
    }
    catch (Exception e) {
      throw logFailure(new RuntimeException(e));
    }
    finally {
      closeStatement(statement);
    }
  }

  /**
   * Determine JDBC 'frame' size, that is the number of results which will be returned to a single
   * {@link java.sql.ResultSet}. This value corresponds to {@link java.sql.Statement#setFetchSize(int)} (which is a user
   * hint, we don't have to honor it), and this method modifies it, ensuring the actual chosen value falls within
   * {@link AvaticaServerConfig#minRowsPerFrame} and {@link AvaticaServerConfig#maxRowsPerFrame}.
   * <p>
   * A value of -1 supplied as input indicates that the client has no preference for fetch size, and can handle
   * unlimited results (at our discretion). Similarly, a value of -1 for {@link AvaticaServerConfig#maxRowsPerFrame}
   * also indicates that there is no upper limit on fetch size on the server side.
   * <p>
   * {@link AvaticaServerConfig#minRowsPerFrame} must be configured to a value greater than 0, because it will be
   * checked against if any additional frames are required (which means one of the input or maximum was set to a value
   * other than -1).
   */
  private int getEffectiveMaxRowsPerFrame(int clientMaxRowsPerFrame)
  {
    // no configured row limit, use the client provided limit
    if (config.getMaxRowsPerFrame() < 0) {
      return adjustForMinumumRowsPerFrame(clientMaxRowsPerFrame);
    }
    // client provided no row limit, use the configured row limit
    if (clientMaxRowsPerFrame < 0) {
      return adjustForMinumumRowsPerFrame(config.getMaxRowsPerFrame());
    }
    return adjustForMinumumRowsPerFrame(Math.min(clientMaxRowsPerFrame, config.getMaxRowsPerFrame()));
  }

  /**
   * coerce fetch size to be, at minimum, {@link AvaticaServerConfig#minRowsPerFrame}
   */
  private int adjustForMinumumRowsPerFrame(int rowsPerFrame)
  {
    final int adjustedRowsPerFrame = Math.max(config.getMinRowsPerFrame(), rowsPerFrame);
    return adjustedRowsPerFrame;
  }

  private static String withEscapeClause(String toEscape)
  {
    return Calcites.escapeStringLiteral(toEscape) + " ESCAPE '\\'";
  }
}
