package com.haozileung.infra.memory;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

public class Memory {

	private DataSource ds;
	private boolean sequence;
	private PreparedStatementHandler psh;

	public Memory(DataSource ds) {
		this(ds, false); // 默认自增，不使用序列
	}

	public Memory(DataSource ds, boolean sequence) {
		this.ds = ds;
		this.sequence = sequence;
		this.psh = PreparedStatementHandler.getInstance();
	}

	public int[] batch(Connection conn, String sql, Object[][] params)
			throws SQLException {
		PreparedStatement stmt = null;
		int[] rows = null;
		try {
			conn.setAutoCommit(false);
			sql = psh.adjustSQL(sequence, sql, params[0]);
			stmt = conn.prepareStatement(sql);
			for (int i = 0; i < params.length; i++) {
				psh.adjustParams(params[i]);
				this.fillStatement(stmt, params[i]);
				stmt.addBatch();
			}
			rows = stmt.executeBatch();
			conn.commit();
		} catch (SQLException e) {
			throw e;
		} finally {
			conn.setAutoCommit(true);
			close(stmt, conn);
		}
		return rows;
	}

	public int[] batch(String sql, Object[][] params) throws SQLException {
		return this.batch(this.getConnection(), sql, params);
	}

	private String camel2underscore(String camel) {
		return psh.camel2underscore(camel);
	}

	private void close(Connection conn) throws SQLException {
		if (conn != null && conn.getAutoCommit()) {
			conn.close();
		}
	}

	private void close(ResultSet rs, Statement stmt, Connection conn)
			throws SQLException {
		if (rs != null) {
			rs.close();
		}
		close(stmt, conn);
	}

	private void close(Statement stmt, Connection conn) throws SQLException {
		if (stmt != null) {
			stmt.close();
		}
		close(conn);
	}

	public <T> int[] create(Class<T> cls, List<T> beans) throws SQLException {
		return create(this.getConnection(), cls, beans, false);
	}

	public <T> int[] create(Class<T> cls, List<T> beans, boolean customKey)
			throws SQLException {
		return create(this.getConnection(), cls, beans, customKey);
	}

	public <T> int create(Class<T> cls, T bean) throws SQLException {
		return this.create(this.getConnection(), cls, bean);
	}

	public <T> int create(Class<T> cls, T bean, boolean customKey)
			throws SQLException {
		return this.create(this.getConnection(), cls, bean, customKey);
	}

	public <T> int[] create(Connection conn, Class<T> cls, List<T> beans)
			throws SQLException {
		return create(conn, cls, beans, false);
	}

	public <T> int[] create(Connection conn, Class<T> cls, List<T> beans,
			boolean customKey) throws SQLException {
		Field[] fields = cls.getDeclaredFields();

		// build SQL
		String table = camel2underscore(cls.getSimpleName());
		String columns = "", questionMarks = "";

		for (Field field : fields) {
			String name = field.getName();
			if (!customKey && name.equals("id")) {
				if (sequence) {
					columns += "id,";
					questionMarks += table + "_SEQ.NEXTVAL,";
				}
			} else {
				columns += camel2underscore(name) + ",";
				questionMarks += "?,";
			}
		}
		columns = columns.substring(0, columns.length() - 1);
		questionMarks = questionMarks.substring(0, questionMarks.length() - 1);
		String sql = String.format("insert into %s (%s) values (%s)", table,
				columns, questionMarks);

		// build parameters */
		int rows = beans.size();
		int cols = customKey ? fields.length : fields.length - 1;

		Object[][] params = new Object[rows][cols];
		try {
			for (int i = 0; i < rows; i++) {
				int j = 0;
				for (Field field : fields) {
					field.setAccessible(true);
					Object value = field.get(beans.get(i));
					if (!customKey && field.getName().equals("id")) {
						continue;
					}
					params[i][j] = value;
					j++;
				}
			}
		} catch (IllegalArgumentException e) {
			throw new SQLException(e);
		} catch (IllegalAccessException e) {
			throw new SQLException(e);
		}
		// execute
		return batch(sql, params);
	}

	public <T> int create(Connection conn, Class<T> cls, T bean)
			throws SQLException {
		return this.create(conn, cls, bean, false);
	}

	public <T> int create(Connection conn, Class<T> cls, T bean,
			boolean customKey) throws SQLException {
		int rows = 0;
		PreparedStatement stmt = null;
		try {
			Field[] fields = cls.getDeclaredFields();

			String table = camel2underscore(cls.getSimpleName());
			String columns = "", questionMarks = "";

			Object[] params = customKey ? new Object[fields.length]
					: new Object[fields.length - 1];

			int j = 0;

			for (Field field : fields) {
				field.setAccessible(true);
				String name = field.getName();
				Object value = field.get(bean);
				/**
				 * 非自定义主键，则ID作为主键且使用序列或自增主键
				 */
				if (!customKey && name.equals("id")) {
					if (sequence) {
						columns += "id,";
						questionMarks += table + "_SEQ.NEXTVAL,";
					}
				} else {
					columns += camel2underscore(name) + ",";
					questionMarks += "?,";
					params[j] = value;
					j++;
				}
			}
			columns = columns.substring(0, columns.length() - 1);
			questionMarks = questionMarks.substring(0,
					questionMarks.length() - 1);
			String sql = String.format("insert into %s (%s) values (%s)",
					table, columns, questionMarks);

			sql = psh.adjust(sequence, sql, params);

			/**
			 * 如果使用非自定义主键，则返回主键ID的值
			 */
			if (!customKey) {
				if (sequence) {
					String generatedColumns[] = { "id" };
					stmt = conn.prepareStatement(sql, generatedColumns);
				} else {
					stmt = conn.prepareStatement(sql,
							Statement.RETURN_GENERATED_KEYS);
				}
			} else {
				stmt = conn.prepareStatement(sql);
			}

			this.fillStatement(stmt, params);
			try {
				rows = stmt.executeUpdate();
			} catch (SQLException e) {
				psh.print(sql, params);
				throw e;
			}
			/**
			 * 如果使用非自定义主键，则返回主键ID的值
			 */
			if (!customKey) {
				ResultSet rs = stmt.getGeneratedKeys();
				long id = 0;
				if (rs.next()) {
					id = rs.getLong(1);
				}
				for (Field field : fields) {
					field.setAccessible(true);
					String name = field.getName();
					if (name.equals("id")) {
						field.set(bean, id);
						break;
					}
				}
			}
		} catch (IllegalArgumentException e) {
			throw new SQLException(e);
		} catch (IllegalAccessException e) {
			throw new SQLException(e);
		} finally {
			close(stmt, conn);
		}
		return rows;
	}

	public <T> int delete(Class<T> cls, long id) throws SQLException {
		return this.delete(this.getConnection(), cls, id);
	}

	public <T> int delete(Connection conn, Class<T> cls, long id)
			throws SQLException {
		String sql = String.format("delete from %s where id=?",
				camel2underscore(cls.getSimpleName()));
		return update(conn, sql, new Object[] { id });
	}

	private void fillStatement(PreparedStatement stmt, Object... params)
			throws SQLException {
		if (params == null)
			return;
		for (int i = 0; i < params.length; i++) {
			stmt.setObject(i + 1, params[i]);
		}
	}

	public Connection getConnection() throws SQLException {
		return this.ds.getConnection();
	}

	public <T> void in(StringBuffer sql, List<Object> params, String operator,
			String field, List<T> values) {
		psh.in(sequence, sql, params, operator, field, values);
	}

	public void pager(StringBuffer sql, List<Object> params, int pageSize,
			int pageNo) {
		psh.pager(sequence, sql, params, pageSize, pageNo);
	}

	public <T> T query(Connection conn, String sql, ResultSetHandler<T> rsh,
			Object... params) throws SQLException {
		PreparedStatement stmt = null;
		ResultSet rs = null;
		T result = null;
		try {
			sql = psh.adjust(sequence, sql, params);
			stmt = conn.prepareStatement(sql);
			this.fillStatement(stmt, params);
			rs = stmt.executeQuery();
			result = rsh.handle(rs);
		} catch (SQLException e) {
			psh.print(sql, params);
			throw e;
		} finally {
			close(rs, stmt, conn);
		}
		return result;
	}

	public <T> T query(Connection conn, StringBuffer sql,
			ResultSetHandler<T> rsh, List<Object> params) throws SQLException {
		return this.query(
				conn,
				sql.toString(),
				rsh,
				params == null ? new Object[] {} : params
						.toArray(new Object[] {}));
	}

	public <T> T query(String sql, ResultSetHandler<T> rsh, Object... params)
			throws SQLException {
		return this.query(this.getConnection(), sql, rsh, params);
	}

	public <T> T query(StringBuffer sql, ResultSetHandler<T> rsh,
			List<Object> params) throws SQLException {
		return this.query(this.getConnection(), sql, rsh, params);
	}

	public <T> T read(Class<T> cls, long id) throws SQLException {
		return this.read(this.getConnection(), cls, id);
	}

	public <T> T read(Connection conn, Class<T> cls, long id)
			throws SQLException {
		String table = camel2underscore(cls.getSimpleName());
		return query(conn, "select * from " + table + " where id=?",
				new BeanHandler<T>(cls), id);
	}

	private String underscore2camel(String underscore) {
		return psh.underscore2camel(underscore);
	}

	public <T> int[] update(Class<T> cls, List<T> beans) throws SQLException {
		return this.update(cls, beans, "id");
	}

	public <T> int[] update(Class<T> cls, List<T> beans, String primaryKey)
			throws SQLException {
		return this.update(this.getConnection(), cls, beans, primaryKey);
	}

	public <T> int update(Class<T> cls, T bean) throws SQLException {
		return this.update(cls, bean, "id");
	}

	public <T> int update(Class<T> cls, T bean, String primaryKey)
			throws SQLException {
		return this.update(this.getConnection(), cls, bean, primaryKey);
	}

	public <T> int[] update(Connection conn, Class<T> cls, List<T> beans)
			throws SQLException {
		return this.update(conn, cls, beans, "id");
	}

	public <T> int[] update(Connection conn, Class<T> cls, List<T> beans,
			String primaryKey) throws SQLException {
		try {
			primaryKey = underscore2camel(primaryKey);
			Field[] fields = cls.getDeclaredFields();
			String columnAndQuestionMarks = "";

			for (Field field : fields) {
				String name = field.getName();
				if (name.equals(primaryKey)) {
				} else {
					columnAndQuestionMarks += camel2underscore(name) + "=?,";
				}
			}
			String table = camel2underscore(cls.getSimpleName());
			columnAndQuestionMarks = columnAndQuestionMarks.substring(0,
					columnAndQuestionMarks.length() - 1);
			String sql = String.format("update %s set %s where %s = ?", table,
					columnAndQuestionMarks, camel2underscore(primaryKey));

			// build parameters
			int rows = beans.size();
			int cols = fields.length;
			Object id = 0;
			Object[][] params = new Object[rows][cols];
			for (int i = 0; i < rows; i++) {
				int j = 0;
				for (Field field : fields) {
					field.setAccessible(true);
					String name = field.getName();
					Object value = field.get(beans.get(i));
					if (name.equals(primaryKey)) {
						id = value;
					} else {
						params[i][j] = value;
						j++;
					}
				}
				params[i][j] = id;
			}
			return batch(conn, sql, params);
		} catch (IllegalArgumentException e) {
			throw new SQLException(e);
		} catch (IllegalAccessException e) {
			throw new SQLException(e);
		}
	}

	public <T> int update(Connection conn, Class<T> cls, T bean)
			throws SQLException {
		return this.update(conn, cls, bean, "id");
	}

	public <T> int update(Connection conn, Class<T> cls, T bean,
			String primaryKey) throws SQLException {
		primaryKey = underscore2camel(primaryKey);
		Object id = 0;
		String columnAndQuestionMarks = "";

		Field[] fields = cls.getDeclaredFields();
		Object[] params = new Object[fields.length];

		try {
			int j = 0;
			for (Field field : fields) {
				field.setAccessible(true);
				String name = field.getName();
				Object value = field.get(bean);
				if (name.equals(primaryKey)) {
					id = value;
				} else {
					columnAndQuestionMarks += camel2underscore(name) + "=?,";
					params[j] = value;
					j++;
				}
			}
			params[j] = id;
		} catch (IllegalArgumentException e) {
			throw new SQLException(e);
		} catch (IllegalAccessException e) {
			throw new SQLException(e);
		}
		String table = camel2underscore(cls.getSimpleName());
		columnAndQuestionMarks = columnAndQuestionMarks.substring(0,
				columnAndQuestionMarks.length() - 1);
		String sql = String.format("update %s set %s where %s = ?", table,
				columnAndQuestionMarks, camel2underscore(primaryKey));
		return update(conn, sql, params);
	}

	public int update(Connection conn, String sql, Object... params)
			throws SQLException {
		PreparedStatement stmt = null;
		int rows = 0;
		try {
			sql = psh.adjust(sequence, sql, params);
			stmt = conn.prepareStatement(sql);
			this.fillStatement(stmt, params);
			rows = stmt.executeUpdate();
		} catch (SQLException e) {
			psh.print(sql, params);
			throw e;
		} finally {
			close(stmt, conn);
		}
		return rows;
	}

	public int update(Connection conn, StringBuffer sql, List<Object> params)
			throws SQLException {
		return this.update(conn, sql.toString(),
				params.toArray(new Object[] {}));
	}

	public int update(String sql, Object... params) throws SQLException {
		return this.update(this.getConnection(), sql, params);
	}

	public int update(StringBuffer sql, List<Object> params)
			throws SQLException {
		return this.update(sql.toString(), params.toArray(new Object[] {}));
	}
}