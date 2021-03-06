package wup.data.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;

import wup.data.Group;
import wup.data.User;

/**
 * MariaDB를 통해 사용자 정보에 접근하는 DAO입니다.
 *
 * @author Eunbin Jeong
 */
public class MariaDbUserDao extends MariaDbDao implements UserDao {

    private static final String TABLE_NAME = "user";

    private static final String SQL_GET_BY_EMAIL = "SELECT * FROM `user` WHERE `email` = ?";
    private static final String SQL_GET_MEMBERS = "SELECT u.* FROM `user` u INNER JOIN `membership` m ON u.`id` = m.`user_id` WHERE m.`group_id` = ?";
    private static final String SQL_PARAM_NAMES = "(`created_at`, `modified_at`, `email`, `auth`, `full_name`, `nickname`, `verified`, `avatar`)";
    private static final String SQL_PARAM_VALUES = "(?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SQL_INSERT = "INSERT INTO `user` " + SQL_PARAM_NAMES + " VALUES " + SQL_PARAM_VALUES;
    private static final String SQL_AUTH_USER = "SELECT * FROM `user` WHERE `email` = ? AND `auth` = ?";
    private static final String SQL_CHECK_AUTH = "SELECT `id` FROM `user` WHERE `id` = ? AND `auth` = ?";
    private static final String SQL_UPDATE_AUTH = "UPDATE `user` SET `modified_at` = ?, `auth` = ? WHERE `id` = ?";

    public MariaDbUserDao(JdbcConnectionProvider connectionProvider) {
        super(connectionProvider);
    }

    /*
     * (non-Javadoc)
     *
     * @see wup.data.access.UserDao#getUser(int)
     */
    @Override
    public DaoResult<User> getUser(int id) {
        return querySingleItem(TABLE_NAME, id, (rs) -> {
            User user = null;

            if (rs.next()) {
                user = getUserFromResultSet(rs);
            }

            return DaoResult.succeed(DaoResult.Action.READ, user);
        });
    }

    /*
     * (non-Javadoc)
     *
     * @see wup.data.access.UserDao#getUser(java.lang.String)
     */
    @Override
    public DaoResult<User> getUser(String email) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL_GET_BY_EMAIL)) {
            stmt.setString(1, email);

            try (ResultSet result = stmt.executeQuery()) {
                User user = null;

                if (result.next()) {
                    user = getUserFromResultSet(result);
                }

                return DaoResult.succeed(DaoResult.Action.READ, user);
            }
        } catch (Exception e) {
            return DaoResult.fail(DaoResult.Action.READ, e);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see wup.data.access.UserDao#getMembers(wup.data.Group)
     */
    @Override
    public DaoResult<List<User>> getMembers(Group group) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL_GET_MEMBERS)) {
            stmt.setInt(1, group.getId());

            try (ResultSet result = stmt.executeQuery()) {
                List<User> members = new ArrayList<User>();

                while (result.next()) {
                    members.add(MariaDbUserDao.getUserFromResultSet(result));
                }

                return DaoResult.succeed(DaoResult.Action.READ, members);
            }
        } catch (Exception e) {
            return DaoResult.fail(DaoResult.Action.READ, e);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see wup.data.access.UserDao#createUser(wup.data.User, java.lang.String)
     */
    @Override
    public DaoResult<User> createUser(User user, String auth) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS)) {
            Timestamp now = new Timestamp(new Date().getTime());

            stmt.setTimestamp(1, now);
            stmt.setTimestamp(2, now);
            stmt.setString(3, user.getEmail());
            stmt.setString(4, hashAuth(auth));
            stmt.setString(5, user.getFullName());
            stmt.setString(6, user.getNickname());
            stmt.setBoolean(7, false);
            stmt.setString(8, user.getAvatar());

            stmt.executeUpdate();

            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                generatedKeys.next();

                int createdUserId = generatedKeys.getInt(1);

                return getUser(createdUserId);
            }
        } catch (Exception e) {
            return DaoResult.fail(DaoResult.Action.CREATE, e);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see wup.data.access.UserDao#updateUser(int, wup.data.User)
     */
    @Override
    public DaoResult<User> updateUser(int id, User user) {
        DaoResult<User> getUserResult = getUser(id);

        if (!getUserResult.didSucceed()) {
            return getUserResult;
        }

        User oldUser = getUserResult.getData();
        List<Entry<String, String>> fieldMap = new ArrayList<>();

        fieldMap.add(new SimpleEntry<String, String>("fullName", "full_name"));
        fieldMap.add(new SimpleEntry<String, String>("nickname", "nickname"));
        fieldMap.add(new SimpleEntry<String, String>("avatar", "avatar"));

        DaoResult<Boolean> updateUserResult = updateSingleItem(TABLE_NAME, id, oldUser, user, fieldMap);

        if (!updateUserResult.didSucceed()) {
            return DaoResult.fail(DaoResult.Action.UPDATE, updateUserResult.getException());
        }

        return getUser(id);
    }

    /*
     * (non-Javadoc)
     *
     * @see wup.data.access.UserDao#deleteUser(int)
     */
    @Override
    public DaoResult<Boolean> deleteUser(int id) {
        return deleteSingleItem(TABLE_NAME, id);
    }

    /*
     * (non-Javadoc)
     *
     * @see wup.data.access.UserDao#authenticate(java.lang.String, java.lang.String)
     */
    @Override
    public DaoResult<User> authenticate(String email, String auth) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SQL_AUTH_USER)) {
            stmt.setString(1, email);
            stmt.setString(2, hashAuth(auth));

            try (ResultSet result = stmt.executeQuery()) {
                User authenticatedUser = null;

                if (result.next()) {
                    authenticatedUser = getUserFromResultSet(result);
                }

                return DaoResult.succeed(DaoResult.Action.READ, authenticatedUser);
            }
        } catch (Exception e) {
            return DaoResult.fail(DaoResult.Action.READ, e);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see wup.data.access.UserDao#updateAuth(int, java.lang.String,
     * java.lang.String)
     */
    @Override
    public DaoResult<Boolean> updateAuth(int id, String oldAuth, String newAuth) {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement checkStmt = conn.prepareStatement(SQL_CHECK_AUTH);
             PreparedStatement updateStmt = conn.prepareStatement(SQL_UPDATE_AUTH)) {
            checkStmt.setInt(1, id);
            checkStmt.setString(2, hashAuth(oldAuth));

            try (ResultSet checkResult = checkStmt.executeQuery()) {
                if (!checkResult.next()) {
                    return DaoResult.succeed(DaoResult.Action.READ, false);
                }
            }

            updateStmt.setTimestamp(1, new Timestamp(new Date().getTime()));
            updateStmt.setString(2, hashAuth(newAuth));
            updateStmt.setInt(3, id);

            updateStmt.executeUpdate();

            return DaoResult.succeed(DaoResult.Action.UPDATE, true);
        } catch (Exception e) {
            return DaoResult.fail(DaoResult.Action.UPDATE, e);
        }
    }

    static User getUserFromResultSet(ResultSet rs) throws SQLException {
        User user = new User();

        user.setId(rs.getInt("id"));
        user.setCreatedAt(rs.getTimestamp("created_at"));
        user.setModifiedAt(rs.getTimestamp("modified_at"));
        user.setEmail(rs.getString("email"));
        user.setFullName(rs.getString("full_name"));
        user.setNickname(rs.getString("nickname"));
        user.setIsVerified(rs.getBoolean("verified"));
        user.setAvatar(rs.getString("avatar"));

        return user;
    }

    private String hashAuth(String auth) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Base64.Encoder enc = Base64.getEncoder();

        md.update(auth.getBytes(StandardCharsets.UTF_8));

        return enc.encodeToString(md.digest());
    }

}
