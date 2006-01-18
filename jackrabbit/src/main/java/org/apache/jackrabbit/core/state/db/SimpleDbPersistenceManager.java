/*
 * Copyright 2004-2005 The Apache Software Foundation or its licensors,
 *                     as applicable.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.state.db;

import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.PropertyId;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.apache.jackrabbit.core.fs.local.LocalFileSystem;
import org.apache.jackrabbit.core.state.AbstractPersistenceManager;
import org.apache.jackrabbit.core.state.ChangeLog;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.NoSuchItemStateException;
import org.apache.jackrabbit.core.state.NodeReferences;
import org.apache.jackrabbit.core.state.NodeReferencesId;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.PMContext;
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.state.ItemState;
import org.apache.jackrabbit.core.state.util.BLOBStore;
import org.apache.jackrabbit.core.state.util.FileSystemBLOBStore;
import org.apache.jackrabbit.core.state.util.Serializer;
import org.apache.jackrabbit.core.value.BLOBFileValue;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.util.Text;
import org.apache.log4j.Logger;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FilterInputStream;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.DatabaseMetaData;

/**
 * <code>SimpleDbPersistenceManager</code> is a generic JDBC-based
 * <code>PersistenceManager</code> for Jackrabbit that persists
 * <code>ItemState</code> and <code>NodeReferences</code> objects using a
 * simple custom binary serialization format (see {@link Serializer}) and a
 * very basic non-normalized database schema (in essence tables with one 'key'
 * and one 'data' column).
 * <p/>
 * It is configured through the following properties:
 * <ul>
 * <li><code>driver</code>: the FQN name of the JDBC driver class</li>
 * <li><code>url</code>: the database url of the form <code>jdbc:subprotocol:subname</code></li>
 * <li><code>user</code>: the database user</li>
 * <li><code>password</code>: the user's password</li>
 * <li><code>schema</code>: type of schema to be used
 * (e.g. <code>mysql</code>, <code>mssql</code>, etc.); </li>
 * <li><code>schemaObjectPrefix</code>: prefix to be prepended to schema objects</li>
 * <li><code>externalBLOBs</code>: if <code>true</code> (the default) BINARY
 * values (BLOBs) are stored in the local file system;
 * if <code>false</code> BLOBs are stored in the database</li>
 * </ul>
 * The required schema objects are automatically created by executing the DDL
 * statements read from the [schema].ddl file. The .ddl file is read from the
 * resources by calling <code>getClass().getResourceAsStream(schema + ".ddl")</code>.
 * Every line in the specified .ddl file is executed separatly by calling
 * <code>java.sql.Statement.execute(String)</code> where every occurence of the
 * the string <code>"${schemaObjectPrefix}"</code> has been replaced with the
 * value of the property <code>schemaObjectPrefix</code>.
 * <p/>
 * The following is a fragment from a sample configuration using MySQL:
 * <pre>
 *   &lt;PersistenceManager class="org.apache.jackrabbit.core.state.db.SimpleDbPersistenceManager"&gt;
 *       &lt;param name="driver" value="com.mysql.jdbc.Driver"/&gt;
 *       &lt;param name="url" value="jdbc:mysql:///test"/&gt;
 *       &lt;param name="schema" value="mysql"/&gt;
 *       &lt;param name="schemaObjectPrefix" value="${wsp.name}_"/&gt;
 *       &lt;param name="externalBLOBs" value="false"/&gt;
 *   &lt;/PersistenceManager&gt;
 * </pre>
 * The following is a fragment from a sample configuration using Daffodil One$DB Embedded:
 * <pre>
 *   &lt;PersistenceManager class="org.apache.jackrabbit.core.state.db.SimpleDbPersistenceManager"&gt;
 *       &lt;param name="driver" value="in.co.daffodil.db.jdbc.DaffodilDBDriver"/&gt;
 *       &lt;param name="url" value="jdbc:daffodilDB_embedded:${wsp.name};path=${wsp.home}/../../databases;create=true"/&gt;
 *       &lt;param name="user" value="daffodil"/&gt;
 *       &lt;param name="password" value="daffodil"/&gt;
 *       &lt;param name="schema" value="daffodil"/&gt;
 *       &lt;param name="schemaObjectPrefix" value="${wsp.name}_"/&gt;
 *       &lt;param name="externalBLOBs" value="false"/&gt;
 *   &lt;/PersistenceManager&gt;
 * </pre>
 * The following is a fragment from a sample configuration using DB2:
 * <pre>
 *   &lt;PersistenceManager class="org.apache.jackrabbit.core.state.db.SimpleDbPersistenceManager"&gt;
 *       &lt;param name="driver" value="com.ibm.db2.jcc.DB2Driver"/&gt;
 *       &lt;param name="url" value="jdbc:db2:test"/&gt;
 *       &lt;param name="schema" value="db2"/&gt;
 *       &lt;param name="schemaObjectPrefix" value="${wsp.name}_"/&gt;
 *       &lt;param name="externalBLOBs" value="false"/&gt;
 *   &lt;/PersistenceManager&gt;
 * </pre>
 * The following is a fragment from a sample configuration using MSSQL:
 * <pre>
 *   &lt;PersistenceManager class="org.apache.jackrabbit.core.state.db.SimpleDbPersistenceManager"&gt;
 *       &lt;param name="driver" value="com.microsoft.jdbc.sqlserver.SQLServerDriver"/&gt;
 *       &lt;param name="url" value="jdbc:microsoft:sqlserver://localhost:1433;;DatabaseName=test;SelectMethod=Cursor;"/&gt;
 *       &lt;param name="schema" value="mssql"/&gt;
 *       &lt;param name="user" value="sa"/&gt;
 *       &lt;param name="password" value=""/&gt;
 *       &lt;param name="schemaObjectPrefix" value="${wsp.name}_"/&gt;
 *       &lt;param name="externalBLOBs" value="false"/&gt;
 *   &lt;/PersistenceManager&gt;
 * </pre>
 * The following is a fragment from a sample configuration using PostgreSQL:
 * <pre>
 *   &lt;PersistenceManager class="org.apache.jackrabbit.core.state.db.SimpleDbPersistenceManager"&gt;
 *       &lt;param name="driver" value="org.postgresql.Driver"/&gt;
 *       &lt;param name="url" value="jdbc:postgresql://localhost/test"/&gt;
 *       &lt;param name="schema" value="postgresql"/&gt;
 *       &lt;param name="user" value="postgres"/&gt;
 *       &lt;param name="password" value="postgres"/&gt;
 *       &lt;param name="schemaObjectPrefix" value="${wsp.name}_"/&gt;
 *       &lt;param name="externalBLOBs" value="false"/&gt;
 *   &lt;/PersistenceManager&gt;
 * </pre>
 * See also {@link DerbyPersistenceManager}.
 */
public class SimpleDbPersistenceManager extends AbstractPersistenceManager {

    /**
     * Logger instance
     */
    private static Logger log = Logger.getLogger(SimpleDbPersistenceManager.class);

    protected static final String SCHEMA_OBJECT_PREFIX_VARIABLE =
            "${schemaObjectPrefix}";

    protected boolean initialized;

    protected String driver;
    protected String url;
    protected String user;
    protected String password;
    protected String schema;
    protected String schemaObjectPrefix;

    protected boolean externalBLOBs;

    // initial size of buffer used to serialize objects
    protected static final int INITIAL_BUFFER_SIZE = 1024;

    // jdbc connection
    protected Connection con;

    // shared prepared statements for NodeState management
    protected PreparedStatement nodeStateInsert;
    protected PreparedStatement nodeStateUpdate;
    protected PreparedStatement nodeStateSelect;
    protected PreparedStatement nodeStateSelectExist;
    protected PreparedStatement nodeStateDelete;

    // shared prepared statements for PropertyState management
    protected PreparedStatement propertyStateInsert;
    protected PreparedStatement propertyStateUpdate;
    protected PreparedStatement propertyStateSelect;
    protected PreparedStatement propertyStateSelectExist;
    protected PreparedStatement propertyStateDelete;

    // shared prepared statements for NodeReference management
    protected PreparedStatement nodeReferenceInsert;
    protected PreparedStatement nodeReferenceUpdate;
    protected PreparedStatement nodeReferenceSelect;
    protected PreparedStatement nodeReferenceSelectExist;
    protected PreparedStatement nodeReferenceDelete;

    // shared prepared statements for BLOB management
    // (if <code>externalBLOBs==false</code>)
    protected PreparedStatement blobInsert;
    protected PreparedStatement blobUpdate;
    protected PreparedStatement blobSelect;
    protected PreparedStatement blobSelectExist;
    protected PreparedStatement blobDelete;

    /**
     * file system where BLOB data is stored
     * (if <code>externalBLOBs==true</code>)
     */
    protected FileSystem blobFS;
    /**
     * BLOBStore that manages BLOB data in the file system
     * (if <code>externalBLOBs==true</code>)
     */
    protected BLOBStore blobStore;

    /**
     * Creates a new <code>SimpleDbPersistenceManager</code> instance.
     */
    public SimpleDbPersistenceManager() {
        schema = "default";
        schemaObjectPrefix = "";
        externalBLOBs = true;
        initialized = false;
    }

    //----------------------------------------------------< setters & getters >
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getSchemaObjectPrefix() {
        return schemaObjectPrefix;
    }

    public void setSchemaObjectPrefix(String schemaObjectPrefix) {
        // make sure prefix is all uppercase
        this.schemaObjectPrefix = schemaObjectPrefix.toUpperCase();
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public boolean isExternalBLOBs() {
        return externalBLOBs;
    }

    public void setExternalBLOBs(boolean externalBLOBs) {
        this.externalBLOBs = externalBLOBs;
    }

    public void setExternalBLOBs(String externalBLOBs) {
        this.externalBLOBs = Boolean.valueOf(externalBLOBs).booleanValue();
    }

    //---------------------------------------------------< PersistenceManager >
    /**
     * {@inheritDoc}
     */
    public void init(PMContext context) throws Exception {
        if (initialized) {
            throw new IllegalStateException("already initialized");
        }

        // setup jdbc connection
        Class.forName(driver);
        con = DriverManager.getConnection(url, user, password);
        con.setAutoCommit(false);

        // make sure schemaObjectPrefix consists of legal name characters only
        prepareSchemaObjectPrefix();

        // check if schema objects exist and create them if necessary
        checkSchema();

        // prepare statements
        nodeStateInsert =
                con.prepareStatement("insert into "
                + schemaObjectPrefix + "NODE (NODE_DATA, NODE_ID) values (?, ?)");
        nodeStateUpdate =
                con.prepareStatement("update "
                + schemaObjectPrefix + "NODE set NODE_DATA = ? where NODE_ID = ?");
        nodeStateSelect =
                con.prepareStatement("select NODE_DATA from "
                + schemaObjectPrefix + "NODE where NODE_ID = ?");
        nodeStateSelectExist =
                con.prepareStatement("select 1 from "
                + schemaObjectPrefix + "NODE where NODE_ID = ?");
        nodeStateDelete =
                con.prepareStatement("delete from "
                + schemaObjectPrefix + "NODE where NODE_ID = ?");

        propertyStateInsert =
                con.prepareStatement("insert into "
                + schemaObjectPrefix + "PROP (PROP_DATA, PROP_ID) values (?, ?)");
        propertyStateUpdate =
                con.prepareStatement("update "
                + schemaObjectPrefix + "PROP set PROP_DATA = ? where PROP_ID = ?");
        propertyStateSelect =
                con.prepareStatement("select PROP_DATA from "
                + schemaObjectPrefix + "PROP where PROP_ID = ?");
        propertyStateSelectExist =
                con.prepareStatement("select 1 from "
                + schemaObjectPrefix + "PROP where PROP_ID = ?");
        propertyStateDelete =
                con.prepareStatement("delete from "
                + schemaObjectPrefix + "PROP where PROP_ID = ?");

        nodeReferenceInsert =
                con.prepareStatement("insert into "
                + schemaObjectPrefix + "REFS (REFS_DATA, NODE_ID) values (?, ?)");
        nodeReferenceUpdate =
                con.prepareStatement("update "
                + schemaObjectPrefix + "REFS set REFS_DATA = ? where NODE_ID = ?");
        nodeReferenceSelect =
                con.prepareStatement("select REFS_DATA from "
                + schemaObjectPrefix + "REFS where NODE_ID = ?");
        nodeReferenceSelectExist =
                con.prepareStatement("select 1 from "
                + schemaObjectPrefix + "REFS where NODE_ID = ?");
        nodeReferenceDelete =
                con.prepareStatement("delete from "
                + schemaObjectPrefix + "REFS where NODE_ID = ?");

        if (externalBLOBs) {
            /**
             * store BLOBs in local file system in a sub directory
             * of the workspace home directory
             */
            LocalFileSystem blobFS = new LocalFileSystem();
            blobFS.setRoot(new File(context.getHomeDir(), "blobs"));
            blobFS.init();
            this.blobFS = blobFS;
            blobStore = new FileSystemBLOBStore(blobFS);
        } else {
            /**
             * store BLOBs in db
             */
            blobStore = new DbBLOBStore();

            blobInsert =
                    con.prepareStatement("insert into "
                    + schemaObjectPrefix + "BINVAL (BINVAL_DATA, BINVAL_ID) values (?, ?)");
            blobUpdate =
                    con.prepareStatement("update "
                    + schemaObjectPrefix + "BINVAL set BINVAL_DATA = ? where BINVAL_ID = ?");
            blobSelect =
                    con.prepareStatement("select BINVAL_DATA from "
                    + schemaObjectPrefix + "BINVAL where BINVAL_ID = ?");
            blobSelectExist =
                    con.prepareStatement("select 1 from "
                    + schemaObjectPrefix + "BINVAL where BINVAL_ID = ?");
            blobDelete =
                    con.prepareStatement("delete from "
                    + schemaObjectPrefix + "BINVAL where BINVAL_ID = ?");
        }

        initialized = true;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void close() throws Exception {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        try {
            // close shared prepared statements
            closeStatement(nodeStateInsert);
            closeStatement(nodeStateUpdate);
            closeStatement(nodeStateSelect);
            closeStatement(nodeStateSelectExist);
            closeStatement(nodeStateDelete);

            closeStatement(propertyStateInsert);
            closeStatement(propertyStateUpdate);
            closeStatement(propertyStateSelect);
            closeStatement(propertyStateSelectExist);
            closeStatement(propertyStateDelete);

            closeStatement(nodeReferenceInsert);
            closeStatement(nodeReferenceUpdate);
            closeStatement(nodeReferenceSelect);
            closeStatement(nodeReferenceSelectExist);
            closeStatement(nodeReferenceDelete);

            if (!externalBLOBs) {
                closeStatement(blobInsert);
                closeStatement(blobUpdate);
                closeStatement(blobSelect);
                closeStatement(blobSelectExist);
                closeStatement(blobDelete);
            } else {
                // close BLOB file system
                blobFS.close();
                blobFS = null;
            }
            blobStore = null;

            // close jdbc connection
            con.close();

        } finally {
            initialized = false;
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void store(ChangeLog changeLog)
            throws ItemStateException {
        ItemStateException ise = null;
        try {
            super.store(changeLog);
        } catch (ItemStateException e) {
            ise = e;
        } finally {
            if (ise == null) {
                // storing the changes succeeded, now commit the changes
                try {
                    con.commit();
                } catch (SQLException e) {
                    String msg = "committing change log failed";
                    log.error(msg, e);
                    throw new ItemStateException(msg, e);
                }
            } else {
                // storing the changes failed, rollback changes
                try {
                    con.rollback();
                } catch (SQLException e) {
                    String msg = "rollback of change log failed";
                    log.error(msg, e);
                }
                // re-throw original exception
                throw ise;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public NodeState load(NodeId id)
            throws NoSuchItemStateException, ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        PreparedStatement stmt = nodeStateSelect;
        synchronized(stmt) {
            ResultSet rs = null;
            InputStream in = null;
            try {
                stmt.setString(1, id.toString());
                stmt.execute();
                rs = stmt.getResultSet();
                if (!rs.next()) {
                    throw new NoSuchItemStateException(id.toString());
                }

                in = rs.getBinaryStream(1);
                NodeState state = createNew(id);
                Serializer.deserialize(state, in);

                return state;
            } catch (Exception e) {
                if (e instanceof NoSuchItemStateException) {
                    throw (NoSuchItemStateException) e;
                }
                String msg = "failed to read node state: " + id;
                log.error(msg, e);
                throw new ItemStateException(msg, e);
            } finally {
                closeStream(in);
                closeResultSet(rs);
                resetStatement(stmt);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public PropertyState load(PropertyId id)
            throws NoSuchItemStateException, ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        PreparedStatement stmt = propertyStateSelect;
        synchronized(stmt) {
            ResultSet rs = null;
            InputStream in = null;
            try {
                stmt.setString(1, id.toString());
                stmt.execute();
                rs = stmt.getResultSet();
                if (!rs.next()) {
                    throw new NoSuchItemStateException(id.toString());
                }

                in = rs.getBinaryStream(1);
                PropertyState state = createNew(id);
                Serializer.deserialize(state, in, blobStore);

                return state;
            } catch (Exception e) {
                if (e instanceof NoSuchItemStateException) {
                    throw (NoSuchItemStateException) e;
                }
                String msg = "failed to read property state: " + id;
                log.error(msg, e);
                throw new ItemStateException(msg, e);
            } finally {
                closeStream(in);
                closeResultSet(rs);
                resetStatement(stmt);
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method uses shared <code>PreparedStatement</code>s which must
     * be executed strictly sequentially. Because this method synchronizes on
     * the persistence manager instance there is no need to synchronize on the
     * shared statement. If the method would not be sychronized the shared
     * statements would have to be synchronized.
     */
    public synchronized void store(NodeState state) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        // check if insert or update
        boolean update = state.getStatus() != ItemState.STATUS_NEW;
        //boolean update = exists((NodeId) state.getId());
        PreparedStatement stmt = (update) ? nodeStateUpdate : nodeStateInsert;

        try {
            ByteArrayOutputStream out =
                    new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
            // serialize node state
            Serializer.serialize(state, out);

            // we are synchronized on this instance, therefore we do not
            // not have to additionally synchronize on the preparedStatement

            stmt.setBytes(1, out.toByteArray());
            stmt.setString(2, state.getId().toString());
            stmt.executeUpdate();

            // there's no need to close a ByteArrayOutputStream
            //out.close();
        } catch (Exception e) {
            String msg = "failed to write node state: " + state.getId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        } finally {
            resetStatement(stmt);
        }
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method uses shared <code>PreparedStatement</code>s which must
     * be executed strictly sequentially. Because this method synchronizes on
     * the persistence manager instance there is no need to synchronize on the
     * shared statement. If the method would not be sychronized the shared
     * statements would have to be synchronized.
     */
    public synchronized void store(PropertyState state)
            throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        // check if insert or update
        boolean update = state.getStatus() != ItemState.STATUS_NEW;
        //boolean update = exists((PropertyId) state.getId());
        PreparedStatement stmt = (update) ? propertyStateUpdate : propertyStateInsert;

        try {
            ByteArrayOutputStream out =
                    new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
            // serialize property state
            Serializer.serialize(state, out, blobStore);

            // we are synchronized on this instance, therefore we do not
            // not have to additionally synchronize on the preparedStatement

            stmt.setBytes(1, out.toByteArray());
            stmt.setString(2, state.getId().toString());
            stmt.executeUpdate();

            // there's no need to close a ByteArrayOutputStream
            //out.close();
        } catch (Exception e) {
            String msg = "failed to write property state: " + state.getId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        } finally {
            resetStatement(stmt);
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void destroy(NodeState state)
            throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        PreparedStatement stmt = nodeStateDelete;
        try {
            stmt.setString(1, state.getId().toString());
            stmt.executeUpdate();
        } catch (Exception e) {
            String msg = "failed to delete node state: " + state.getId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        } finally {
            resetStatement(stmt);
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void destroy(PropertyState state)
            throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        // make sure binary values (BLOBs) are properly removed
        InternalValue[] values = state.getValues();
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                InternalValue val = values[i];
                if (val != null) {
                    if (val.getType() == PropertyType.BINARY) {
                        BLOBFileValue blobVal = (BLOBFileValue) val.internalValue();
                        // delete internal resource representation of BLOB value
                        blobVal.delete(true);
                        // also remove from BLOBStore
                        String blobId = blobStore.createId((PropertyId) state.getId(), i);
                        try {
                            blobStore.remove(blobId);
                        } catch (Exception e) {
                            log.warn("failed to remove from BLOBStore: " + blobId, e);
                        }
                    }
                }
            }
        }

        PreparedStatement stmt = propertyStateDelete;
        try {
            stmt.setString(1, state.getId().toString());
            stmt.executeUpdate();
        } catch (Exception e) {
            String msg = "failed to delete property state: " + state.getId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        } finally {
            resetStatement(stmt);
        }
    }

    /**
     * {@inheritDoc}
     */
    public NodeReferences load(NodeReferencesId targetId)
            throws NoSuchItemStateException, ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        PreparedStatement stmt = nodeReferenceSelect;
        synchronized(stmt) {
            ResultSet rs = null;
            InputStream in = null;
            try {
                stmt.setString(1, targetId.toString());
                stmt.execute();
                rs = stmt.getResultSet();
                if (!rs.next()) {
                    throw new NoSuchItemStateException(targetId.toString());
                }

                in = rs.getBinaryStream(1);
                NodeReferences refs = new NodeReferences(targetId);
                Serializer.deserialize(refs, in);

                return refs;
            } catch (Exception e) {
                if (e instanceof NoSuchItemStateException) {
                    throw (NoSuchItemStateException) e;
                }
                String msg = "failed to read node references: " + targetId;
                log.error(msg, e);
                throw new ItemStateException(msg, e);
            } finally {
                closeStream(in);
                closeResultSet(rs);
                resetStatement(stmt);
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method uses shared <code>PreparedStatement</code>s which must
     * be executed strictly sequentially. Because this method synchronizes on
     * the persistence manager instance there is no need to synchronize on the
     * shared statement. If the method would not be sychronized the shared
     * statements would have to be synchronized.
     */
    public synchronized void store(NodeReferences refs)
            throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        // check if insert or update
        boolean update = exists(refs.getTargetId());
        PreparedStatement stmt = (update) ? nodeReferenceUpdate : nodeReferenceInsert;

        try {
            ByteArrayOutputStream out =
                    new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
            // serialize references
            Serializer.serialize(refs, out);

            // we are synchronized on this instance, therefore we do not
            // not have to additionally synchronize on the preparedStatement

            stmt.setBytes(1, out.toByteArray());
            stmt.setString(2, refs.getTargetId().toString());
            stmt.executeUpdate();

            // there's no need to close a ByteArrayOutputStream
            //out.close();
        } catch (Exception e) {
            String msg = "failed to write node references: " + refs.getTargetId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        } finally {
            resetStatement(stmt);
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void destroy(NodeReferences refs)
            throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        PreparedStatement stmt = nodeReferenceDelete;
        try {
            stmt.setString(1, refs.getTargetId().toString());
            stmt.executeUpdate();
        } catch (Exception e) {
            String msg = "failed to delete node references: " + refs.getTargetId();
            log.error(msg, e);
            throw new ItemStateException(msg, e);
        } finally {
            resetStatement(stmt);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean exists(NodeId id) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        PreparedStatement stmt = nodeStateSelectExist;
        synchronized(stmt) {
            ResultSet rs = null;
            try {
                stmt.setString(1, id.toString());
                stmt.execute();
                rs = stmt.getResultSet();

                // a node state exists if the result has at least one entry
                return rs.next();
            } catch (Exception e) {
                String msg = "failed to check existence of node state: " + id;
                log.error(msg, e);
                throw new ItemStateException(msg, e);
            } finally {
                closeResultSet(rs);
                resetStatement(stmt);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean exists(PropertyId id) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        PreparedStatement stmt = propertyStateSelectExist;
        synchronized(stmt) {
            ResultSet rs = null;
            try {
                stmt.setString(1, id.toString());
                stmt.execute();
                rs = stmt.getResultSet();

                // a property state exists if the result has at least one entry
                return rs.next();
            } catch (Exception e) {
                String msg = "failed to check existence of property state: " + id;
                log.error(msg, e);
                throw new ItemStateException(msg, e);
            } finally {
                closeResultSet(rs);
                resetStatement(stmt);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean exists(NodeReferencesId targetId) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        PreparedStatement stmt = nodeReferenceSelectExist;
        synchronized(stmt) {
            ResultSet rs = null;
            try {
                stmt.setString(1, targetId.toString());
                stmt.execute();
                rs = stmt.getResultSet();

                // a reference exists if the result has at least one entry
                return rs.next();
            } catch (Exception e) {
                String msg = "failed to check existence of node references: "
                        + targetId;
                log.error(msg, e);
                throw new ItemStateException(msg, e);
            } finally {
                closeResultSet(rs);
                resetStatement(stmt);
            }
        }
    }

    //-------------------------------------------------< misc. helper methods >
    /**
     * Resets the given <code>PreparedStatement</code> by clearing the parameters
     * and warnings contained.
     * <p/>
     * NOTE: This method MUST be called in a synchronized context as neither
     * this method nor the <code>PreparedStatement</code> instance on which it
     * operates are thread safe.
     *
     * @param stmt The <code>PreparedStatement</code> to reset. If
     *             <code>null</code> this method does nothing.
     */
    protected void resetStatement(PreparedStatement stmt) {
        if (stmt != null) {
            try {
                stmt.clearParameters();
                stmt.clearWarnings();
            } catch (SQLException se) {
                logException("failed resetting PreparedStatement", se);
            }
        }
    }

    protected void closeResultSet(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException se) {
                logException("failed closing ResultSet", se);
            }
        }
    }

    protected void closeStream(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignore) {
            }
        }
    }

    protected void closeStatement(Statement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException se) {
                logException("failed closing Statement", se);
            }
        }
    }

    protected void logException(String message, SQLException se) {
        if (message != null) {
            log.error(message);
        }
        log.error("    reason: " + se.getMessage());
        log.error("state/code: " + se.getSQLState() + "/" + se.getErrorCode());
        log.debug("      dump:", se);
    }

    /**
     * Makes sure that <code>schemaObjectPrefix</code> does only consist of
     * characters that are allowed in names on the target database. Illegal
     * characters will be escaped as necessary.
     *
     * @throws Exception if an error occurs
     */
    protected void prepareSchemaObjectPrefix() throws Exception {
        DatabaseMetaData metaData = con.getMetaData();
        String legalChars = metaData.getExtraNameCharacters();
        legalChars += "ABCDEFGHIJKLMNOPQRSTUVWXZY0123456789_";

        String prefix = schemaObjectPrefix.toUpperCase();
        StringBuffer escaped = new StringBuffer();
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (legalChars.indexOf(c) == -1) {
                escaped.append("_x");
                String hex = Integer.toHexString(c);
                escaped.append("0000".toCharArray(), 0, 4 - hex.length());
                escaped.append(hex);
                escaped.append("_");
            } else {
                escaped.append(c);
            }
        }
        schemaObjectPrefix = escaped.toString();
    }

    /**
     * Checks if the required schema objects exist and creates them if they
     * don't exist yet.
     *
     * @throws Exception if an error occurs
     */
    protected void checkSchema() throws Exception {
        DatabaseMetaData metaData = con.getMetaData();
        String tableName = schemaObjectPrefix + "NODE";
        if (metaData.storesLowerCaseIdentifiers()) {
            tableName = tableName.toLowerCase();
        } else if (metaData.storesUpperCaseIdentifiers()) {
            tableName = tableName.toUpperCase();
        }

        ResultSet rs = metaData.getTables(null, null, tableName, null);
        boolean schemaExists;
        try {
            schemaExists = rs.next();
        } finally {
            rs.close();
        }

        if (!schemaExists) {
            // read ddl from resources
            InputStream in = getClass().getResourceAsStream(schema + ".ddl");
            if (in == null) {
                String msg = "Configuration error: unknown schema '" + schema + "'";
                log.debug(msg);
                throw new RepositoryException(msg);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            Statement stmt = con.createStatement();
            try {
                String sql = reader.readLine();
                while (sql != null) {
                    // replace prefix variable
                    sql = Text.replace(sql, SCHEMA_OBJECT_PREFIX_VARIABLE, schemaObjectPrefix);
                    // execute sql stmt
                    stmt.executeUpdate(sql);
                    // read next sql stmt
                    sql = reader.readLine();
                }
                // commit the changes
                con.commit();
            } finally {
                closeStream(in);
                closeStatement(stmt);
            }
        }
    }

    //--------------------------------------------------------< inner classes >
    class DbBLOBStore implements BLOBStore {
        /**
         * {@inheritDoc}
         */
        public String createId(PropertyId id, int index) {
            // the blobId is a simple string concatenation of id plus index
            StringBuffer sb = new StringBuffer();
            sb.append(id.toString());
            sb.append('[');
            sb.append(index);
            sb.append(']');
            return sb.toString();
        }

        /**
         * {@inheritDoc}
         */
        public InputStream get(String blobId) throws Exception {
            PreparedStatement stmt = blobSelect;
            synchronized(stmt) {
                try {
                    stmt.setString(1, blobId);
                    stmt.execute();
                    final ResultSet rs = stmt.getResultSet();
                    if (!rs.next()) {
                        closeResultSet(rs);
                        throw new Exception("no such BLOB: " + blobId);
                    }
                    InputStream in = rs.getBinaryStream(1);
                    if (in == null) {
                        // some databases treat zero-length values as NULL;
                        // return empty InputStream in such a case
                        closeResultSet(rs);
                        return new ByteArrayInputStream(new byte[0]);
                    }

                    /**
                     * return an InputStream wrapper in order to
                     * close the ResultSet when the stream is closed
                     */
                    return new FilterInputStream(in) {
                        public void close() throws IOException {
                            in.close();
                            // now it's safe to close ResultSet
                            closeResultSet(rs);
                        }
                    };
                } finally {
                    resetStatement(stmt);
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        public synchronized void put(String blobId, InputStream in, long size)
                throws Exception {
            PreparedStatement stmt = blobSelectExist;
            try {
                stmt.setString(1, blobId);
                stmt.execute();
                ResultSet rs = stmt.getResultSet();
                // a BLOB exists if the result has at least one entry
                boolean exists = rs.next();
                resetStatement(stmt);
                closeResultSet(rs);

                stmt = (exists) ? blobUpdate : blobInsert;
                stmt.setBinaryStream(1, in, (int) size);
                stmt.setString(2, blobId);
                stmt.executeUpdate();
            } finally {
                resetStatement(stmt);
            }
        }

        /**
         * {@inheritDoc}
         */
        public synchronized boolean remove(String blobId) throws Exception {
            PreparedStatement stmt = blobDelete;
            try {
                stmt.setString(1, blobId);
                return stmt.executeUpdate() == 1;
            } finally {
                resetStatement(stmt);
            }
        }
    }
}
