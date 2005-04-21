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
package org.apache.jackrabbit.core.state.mem;

import org.apache.jackrabbit.core.BLOBFileValue;
import org.apache.jackrabbit.core.InternalValue;
import org.apache.jackrabbit.core.ItemId;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.PropertyId;
import org.apache.jackrabbit.core.QName;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.apache.jackrabbit.core.fs.FileSystemPathUtil;
import org.apache.jackrabbit.core.fs.FileSystemResource;
import org.apache.jackrabbit.core.fs.local.LocalFileSystem;
import org.apache.jackrabbit.core.state.AbstractPersistenceManager;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.NoSuchItemStateException;
import org.apache.jackrabbit.core.state.NodeReferences;
import org.apache.jackrabbit.core.state.NodeReferencesId;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.PMContext;
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.state.obj.BLOBStore;
import org.apache.jackrabbit.core.state.obj.ObjectPersistenceManager;
import org.apache.log4j.Logger;

import javax.jcr.PropertyType;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * <code>InMemPersistenceManager</code> is a very simple <code>HashMap</code>-based
 * <code>PersistenceManager</code> for Jackrabbit that keeps all data in memory
 * and that is capable of storing and loading its contents using a simple custom
 * serialization format.
 * <p/>
 * It is configured through the following properties:
 * <ul>
 * <li><code>initialCapacity</code>: initial capacity of the hash map used to store the data</li>
 * <li><code>loadFactor</code>: load factor of the hash map used to store the data</li>
 * <li><code>persistent</code>: if <code>true</code> the contents of the hash map
 * is loaded on startup and stored on shutdown;
 * if <code>false</code> nothing is persisted</li>
 * </ul>
 * <b>Please note that this class should only be used for testing purposes.</b>
 */
public class InMemPersistenceManager extends AbstractPersistenceManager
        implements BLOBStore {

    private static Logger log = Logger.getLogger(InMemPersistenceManager.class);

    protected boolean initialized;

    protected Map stateStore;
    protected Map refsStore;

    // initial size of buffer used to serialize objects
    protected static final int INITIAL_BUFFER_SIZE = 1024;

    // some constants used in serialization
    protected static final String STATE_FILE_PATH = "/data/.state.bin";
    protected static final String REFS_FILE_PATH = "/data/.refs.bin";
    protected static final byte NODE_ENTRY = 0;
    protected static final byte PROP_ENTRY = 1;

    // file system where BLOB data is stored
    protected FileSystem blobFS;

    /**
     * file system where the content of the hash maps are read from/written to
     * (if <code>persistent==true</code>)
     */
    protected FileSystem wspFS;

    // initial capacity
    protected int initialCapacity = 32768;
    // load factor for the hash map
    protected float loadFactor = 0.75f;
    // should hash map be persisted?
    protected boolean persistent = true;

    /**
     * Creates a new <code>InMemPersistenceManager</code> instance.
     */
    public InMemPersistenceManager() {
        initialized = false;
    }

    public void setInitialCapacity(int initialCapacity) {
        this.initialCapacity = initialCapacity;
    }

    public void setInitialCapacity(String initialCapacity) {
        this.initialCapacity = Integer.valueOf(initialCapacity).intValue();
    }

    public String getInitialCapacity() {
        return Integer.toString(initialCapacity);
    }

    public void setLoadFactor(float loadFactor) {
        this.loadFactor = loadFactor;
    }

    public void setLoadFactor(String loadFactor) {
        this.loadFactor = Float.valueOf(loadFactor).floatValue();
    }

    public String getLoadFactor() {
        return Float.toString(loadFactor);
    }

    public boolean isPersistent() {
        return persistent;
    }

    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    public void setPersistent(String persistent) {
        this.persistent = Boolean.valueOf(persistent).booleanValue();
    }

    protected static String buildBlobFilePath(String parentUUID, QName propName, int index) {
        StringBuffer sb = new StringBuffer();
        char[] chars = parentUUID.toCharArray();
        int cnt = 0;
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '-') {
                continue;
            }
            //if (cnt > 0 && cnt % 4 == 0) {
            if (cnt == 2 || cnt == 4) {
                sb.append(FileSystem.SEPARATOR_CHAR);
            }
            sb.append(chars[i]);
            cnt++;
        }
        sb.append(FileSystem.SEPARATOR_CHAR);
        sb.append(FileSystemPathUtil.escapeName(propName.toString()));
        sb.append('.');
        sb.append(index);
        sb.append(".bin");
        return sb.toString();
    }

    /**
     * Reads the content of the hash maps from the file system
     *
     * @throws Exception if an error occurs
     */
    public synchronized void loadContents() throws Exception {
        // read item states
        FileSystemResource fsRes = new FileSystemResource(wspFS, STATE_FILE_PATH);
        if (!fsRes.exists()) {
            return;
        }
        BufferedInputStream bis = new BufferedInputStream(fsRes.getInputStream());
        DataInputStream in = new DataInputStream(bis);

        try {
            int n = in.readInt();   // number of entries
            while (n-- > 0) {
                byte type = in.readByte();  // entry type
                ItemId id;
                if (type == NODE_ENTRY) {
                    // entry type: node
                    String s = in.readUTF();    // id
                    id = NodeId.valueOf(s);
                } else {
                    // entry type: node
                    String s = in.readUTF();    // id
                    id = NodeId.valueOf(s);
                }
                int length = in.readInt();  // data length
                byte[] data = new byte[length];
                in.read(data);  // data
                // store in map
                stateStore.put(id, data);
            }
        } finally {
            in.close();
        }

        // read references
        fsRes = new FileSystemResource(wspFS, REFS_FILE_PATH);
        bis = new BufferedInputStream(fsRes.getInputStream());
        in = new DataInputStream(bis);

        try {
            int n = in.readInt();   // number of entries
            while (n-- > 0) {
                String s = in.readUTF();    // target id
                NodeReferencesId id = (NodeReferencesId) NodeReferencesId.valueOf(s);
                int length = in.readInt();  // data length
                byte[] data = new byte[length];
                in.read(data);  // data
                // store in map
                refsStore.put(id, data);
            }
        } finally {
            in.close();
        }
    }

    /**
     * Writes the content of the hash maps to the file system
     *
     * @throws Exception if an error occurs
     */
    public synchronized void storeContents() throws Exception {
        // write item states
        FileSystemResource fsRes = new FileSystemResource(wspFS, STATE_FILE_PATH);
        fsRes.makeParentDirs();
        BufferedOutputStream bos = new BufferedOutputStream(fsRes.getOutputStream());
        DataOutputStream out = new DataOutputStream(bos);

        try {

            out.writeInt(stateStore.size());    // number of entries
            // entries
            Iterator iterKeys = stateStore.keySet().iterator();
            while (iterKeys.hasNext()) {
                ItemId id = (ItemId) iterKeys.next();
                if (id.denotesNode()) {
                    out.writeByte(NODE_ENTRY);  // entry type
                } else {
                    out.writeByte(PROP_ENTRY);  // entry type
                }
                out.writeUTF(id.toString());    // id
                byte[] data = (byte[]) stateStore.get(id);
                out.writeInt(data.length);  // data length
                out.write(data);    // data
            }
        } finally {
            out.close();
        }

        // write references
        fsRes = new FileSystemResource(wspFS, REFS_FILE_PATH);
        fsRes.makeParentDirs();
        bos = new BufferedOutputStream(fsRes.getOutputStream());
        out = new DataOutputStream(bos);

        try {
            out.writeInt(refsStore.size()); // number of entries
            // entries
            Iterator iterKeys = refsStore.keySet().iterator();
            while (iterKeys.hasNext()) {
                NodeReferencesId id = (NodeReferencesId) iterKeys.next();
                out.writeUTF(id.toString());    // target id
                byte[] data = (byte[]) refsStore.get(id);
                out.writeInt(data.length);  // data length
                out.write(data);    // data
            }
        } finally {
            out.close();
        }
    }

    //------------------------------------------------------------< BLOBStore >
    /**
     * {@inheritDoc}
     */
    public FileSystemResource get(String blobId) throws Exception {
        return new FileSystemResource(blobFS, blobId);
    }

    /**
     * {@inheritDoc}
     */
    public String put(PropertyId id, int index, InputStream in, long size) throws Exception {
        String path = buildBlobFilePath(id.getParentUUID(), id.getName(), index);
        OutputStream out = null;
        FileSystemResource internalBlobFile = new FileSystemResource(blobFS, path);
        internalBlobFile.makeParentDirs();
        try {
            out = new BufferedOutputStream(internalBlobFile.getOutputStream());
            byte[] buffer = new byte[8192];
            int read = 0;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } finally {
            out.close();
        }
        return path;
    }

    /**
     * {@inheritDoc}
     */
    public boolean remove(String blobId) throws Exception {
        FileSystemResource res = new FileSystemResource(blobFS, blobId);
        if (!res.exists()) {
            return false;
        }
        // delete resource and prune empty parent folders
        res.delete(true);
        return true;
    }

    //---------------------------------------------------< PersistenceManager >
    /**
     * {@inheritDoc}
     */
    public void init(PMContext context) throws Exception {
        if (initialized) {
            throw new IllegalStateException("already initialized");
        }

        stateStore = new HashMap(initialCapacity, loadFactor);
        refsStore = new HashMap(initialCapacity, loadFactor);

        wspFS = context.getFileSystem();

        /**
         * store blob's in local file system in a sub directory
         * of the workspace home directory
         */
        LocalFileSystem blobFS = new LocalFileSystem();
        blobFS.setRoot(new File(context.getHomeDir(), "blobs"));
        blobFS.init();
        this.blobFS = blobFS;

        if (persistent) {
            // deserialize contents of state and refs stores
            loadContents();
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
            if (persistent) {
                // serialize contents of state and refs stores
                storeContents();
            } else {
                // clean out blob store
                try {
                    String[] folders = blobFS.listFolders("/");
                    for (int i = 0; i < folders.length; i++) {
                        blobFS.deleteFolder(folders[i]);
                    }
                    String[] files = blobFS.listFiles("/");
                    for (int i = 0; i < files.length; i++) {
                        blobFS.deleteFile(files[i]);
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            // close blob store
            blobFS.close();
            blobFS = null;

            stateStore.clear();
            stateStore = null;
            refsStore.clear();
            refsStore = null;
        } finally {
            initialized = false;
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized NodeState load(NodeId id)
            throws NoSuchItemStateException, ItemStateException {

        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        byte[] data = (byte[]) stateStore.get(id);
        if (data == null) {
            throw new NoSuchItemStateException(id.toString());
        }

        ByteArrayInputStream in = new ByteArrayInputStream(data);
        try {
            NodeState state = createNew(id);
            ObjectPersistenceManager.deserialize(state, in);
            return state;
        } catch (Exception e) {
            String msg = "failed to read node state: " + id;
            log.debug(msg);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized PropertyState load(PropertyId id)
            throws NoSuchItemStateException, ItemStateException {

        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        byte[] data = (byte[]) stateStore.get(id);
        if (data == null) {
            throw new NoSuchItemStateException(id.toString());
        }

        ByteArrayInputStream in = new ByteArrayInputStream(data);
        try {
            PropertyState state = createNew(id);
            ObjectPersistenceManager.deserialize(state, in, this);
            return state;
        } catch (Exception e) {
            String msg = "failed to read property state: " + id;
            log.debug(msg);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    protected void store(NodeState state) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
            // serialize node state
            ObjectPersistenceManager.serialize(state, out);

            // store in serialized format in map for better memory efficiency
            stateStore.put(state.getId(), out.toByteArray());
            // there's no need to close a ByteArrayOutputStream
            //out.close();
        } catch (Exception e) {
            String msg = "failed to write node state: " + state.getId();
            log.debug(msg);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    protected void store(PropertyState state) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
            // serialize property state
            ObjectPersistenceManager.serialize(state, out, this);

            // store in serialized format in map for better memory efficiency
            stateStore.put(state.getId(), out.toByteArray());
            // there's no need to close a ByteArrayOutputStream
            //out.close();
        } catch (Exception e) {
            String msg = "failed to store property state: " + state.getId();
            log.debug(msg);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    protected void destroy(NodeState state) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        // remove node state
        stateStore.remove(state.getId());
    }

    /**
     * {@inheritDoc}
     */
    protected void destroy(PropertyState state) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        // delete binary values (stored as files)
        InternalValue[] values = state.getValues();
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                InternalValue val = values[i];
                if (val != null) {
                    if (val.getType() == PropertyType.BINARY) {
                        BLOBFileValue blobVal = (BLOBFileValue) val.internalValue();
                        // delete blob file and prune empty parent folders
                        blobVal.delete(true);
                    }
                }
            }
        }

        // remove property state
        stateStore.remove(state.getId());
    }

    /**
     * {@inheritDoc}
     */
    public synchronized NodeReferences load(NodeReferencesId id)
            throws NoSuchItemStateException, ItemStateException {

        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        byte[] data = (byte[]) refsStore.get(id);
        if (data == null) {
            throw new NoSuchItemStateException(id.getUUID());
        }

        ByteArrayInputStream in = new ByteArrayInputStream(data);
        try {
            NodeReferences refs = new NodeReferences(id);
            ObjectPersistenceManager.deserialize(refs, in);
            return refs;
        } catch (Exception e) {
            String msg = "failed to load references: " + id.getUUID();
            log.debug(msg);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    protected void store(NodeReferences refs) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
            // serialize references
            ObjectPersistenceManager.serialize(refs, out);

            // store in serialized format in map for better memory efficiency
            stateStore.put(refs.getTargetId(), out.toByteArray());
            // there's no need to close a ByteArrayOutputStream
            //out.close();
        } catch (Exception e) {
            String msg = "failed to store references: " + refs.getTargetId();
            log.debug(msg);
            throw new ItemStateException(msg, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    protected void destroy(NodeReferences refs) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }

        // remove node references
        stateStore.remove(refs.getTargetId());
    }

    /**
     * {@inheritDoc}
     */
    public boolean exists(PropertyId id) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }
        return stateStore.containsKey(id);
    }

    /**
     * {@inheritDoc}
     */
    public boolean exists(NodeId id) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }
        return stateStore.containsKey(id);
    }

    /**
     * {@inheritDoc}
     */
    public boolean exists(NodeReferencesId id) throws ItemStateException {
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }
        return refsStore.containsKey(id);
    }
}
