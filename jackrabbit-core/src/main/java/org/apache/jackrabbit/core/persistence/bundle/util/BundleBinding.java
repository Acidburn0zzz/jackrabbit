/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.persistence.bundle.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.jackrabbit.core.persistence.util.BLOBStore;
import org.apache.jackrabbit.core.persistence.util.ResourceBasedBLOBStore;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.PropertyId;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.core.value.BLOBFileValue;
import org.apache.jackrabbit.core.data.DataStore;
import org.apache.jackrabbit.core.nodetype.NodeDefId;
import org.apache.jackrabbit.core.nodetype.PropDefId;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.uuid.UUID;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.jcr.PropertyType;

/**
 * This Class implements efficient serialization methods for item states.
 */
public class BundleBinding extends ItemStateBinding {

    /** the cvs/svn id */
    static final String CVS_ID = "$URL$ $Rev$ $Date$";

    /**
     * default logger
     */
    private static Logger log = LoggerFactory.getLogger(BundleBinding.class);

    /**
     * Creates a new bundle binding
     *
     * @param errorHandling the error handling
     * @param blobStore the blobstore for retrieving blobs
     * @param nsIndex the namespace index
     * @param nameIndex the name index
     */
    public BundleBinding(ErrorHandling errorHandling, BLOBStore blobStore,
                         StringIndex nsIndex, StringIndex nameIndex, DataStore dataStore) {
        super(errorHandling, blobStore, nsIndex, nameIndex, dataStore);
    }

    /**
     * Deserializes a <code>NodePropBundle</code> from a data input stream.
     *
     * @param in the input stream
     * @param id the nodeid for the new budle
     * @return the bundle
     * @throws IOException if an I/O error occurs.
     */
    public NodePropBundle readBundle(DataInputStream in, NodeId id)
            throws IOException {

        NodePropBundle bundle = new NodePropBundle(id);

        // read version and primary type...special handling
        int index = in.readInt();

        // get version
        int version = (index >> 24) & 0xff;
        index &= 0x00ffffff;
        String uri = nsIndex.indexToString(index);
        String local = nameIndex.indexToString(in.readInt());
        QName nodeTypeName = new QName(uri, local);

        // primaryType
        bundle.setNodeTypeName(nodeTypeName);

        // parentUUID
        bundle.setParentId(readID(in));

        // definitionId
        bundle.setNodeDefId(NodeDefId.valueOf(in.readUTF()));

        // mixin types
        Set mixinTypeNames = new HashSet();
        QName name = readIndexedQName(in);
        while (name != null) {
            mixinTypeNames.add(name);
            name = readIndexedQName(in);
        }
        bundle.setMixinTypeNames(mixinTypeNames);

        // properties
        name = readIndexedQName(in);
        while (name != null) {
            PropertyId pId = new PropertyId(bundle.getId(), name);
            NodePropBundle.PropertyEntry pState = readPropertyEntry(in, pId);
            bundle.addProperty(pState);
            name = readIndexedQName(in);
        }

        // set referenceable flag
        bundle.setReferenceable(in.readBoolean());

        // child nodes (list of uuid/name pairs)
        NodeId childId = readID(in);
        while (childId != null) {
            bundle.addChildNodeEntry(readQName(in), childId);
            childId = readID(in);
        }

        // read modcount, since version 1.0
        if (version >= VERSION_1) {
            bundle.setModCount(readModCount(in));
        }
        return bundle;
    }

    /**
     * Checks a <code>NodePropBundle</code> from a data input stream.
     *
     * @param in the input stream
     * @return <code>true</code> if the data is valid;
     *         <code>false</code> otherwise.
     */
    public boolean checkBundle(DataInputStream in) {
        int version;
        // primaryType & version
        try {
            // read version and primary type...special handling
            int index = in.readInt();

            // get version
            version = (index >> 24) & 0xff;
            index &= 0x00ffffff;
            String uri = nsIndex.indexToString(index);
            String local = nameIndex.indexToString(in.readInt());
            QName nodeTypeName = new QName(uri, local);

            log.info("Serialzation Version: " + version);
            log.info("NodeTypeName: " + nodeTypeName);
        } catch (IOException e) {
            log.error("Error while reading NodeTypeName: " + e);
            return false;
        }
        try {
            UUID parentUuid = readUUID(in);
            log.info("ParentUUID: " + parentUuid);
        } catch (IOException e) {
            log.error("Error while reading ParentUUID: " + e);
            return false;
        }
        try {
            String definitionId = in.readUTF();
            log.info("DefinitionId: " + definitionId);
        } catch (IOException e) {
            log.error("Error while reading DefinitionId: " + e);
            return false;
        }
        try {
            QName mixinName = readIndexedQName(in);
            while (mixinName != null) {
                log.info("MixinTypeName: " + mixinName);
                mixinName = readIndexedQName(in);
            }
        } catch (IOException e) {
            log.error("Error while reading MixinTypes: " + e);
            return false;
        }
        try {
            QName propName = readIndexedQName(in);
            while (propName != null) {
                log.info("PropertyName: " + propName);
                if (!checkPropertyState(in)) {
                    return false;
                }
                propName = readIndexedQName(in);
            }
        } catch (IOException e) {
            log.error("Error while reading property names: " + e);
            return false;
        }
        try {
            boolean hasUUID = in.readBoolean();
            log.info("hasUUID: " + hasUUID);
        } catch (IOException e) {
            log.error("Error while reading 'hasUUID': " + e);
            return false;
        }
        try {
            UUID cneUUID = readUUID(in);
            while (cneUUID != null) {
                QName cneName = readQName(in);
                log.info("ChildNodentry: " + cneUUID + ":" + cneName);
                cneUUID = readUUID(in);
            }
        } catch (IOException e) {
            log.error("Error while reading child node entry: " + e);
            return false;
        }

        if (version >= VERSION_1) {
            try {
                short modCount = readModCount(in);
                log.info("modCount: " + modCount);
            } catch (IOException e) {
                log.error("Error while reading mod cout: " + e);
                return false;
            }
        }

        return true;
    }

    /**
     * Serializes a <code>NodePropBundle</code> to a data output stream
     *
     * @param out the output stream
     * @param bundle the bundle to serialize
     * @throws IOException if an I/O error occurs.
     */
    public void writeBundle(DataOutputStream out, NodePropBundle bundle)
            throws IOException {
        long size = out.size();

        // primaryType and version
        out.writeInt((VERSION_CURRENT << 24) | nsIndex.stringToIndex(bundle.getNodeTypeName().getNamespaceURI()));
        out.writeInt(nameIndex.stringToIndex(bundle.getNodeTypeName().getLocalName()));

        // parentUUID
        writeID(out, bundle.getParentId());

        // definitionId
        out.writeUTF(bundle.getNodeDefId().toString());

        // mixin types
        Iterator iter = bundle.getMixinTypeNames().iterator();
        while (iter.hasNext()) {
            writeIndexedQName(out, (QName) iter.next());
        }
        writeIndexedQName(out, null);

        // properties
        iter = bundle.getPropertyNames().iterator();
        while (iter.hasNext()) {
            QName pName = (QName) iter.next();
            NodePropBundle.PropertyEntry pState = bundle.getPropertyEntry(pName);
            if (pState == null) {
                log.error("PropertyState missing in bundle: " + pName);
            } else {
                writeIndexedQName(out, pName);
                writeState(out, pState);
            }
        }
        writeIndexedQName(out, null);

        // write uuid flag
        out.writeBoolean(bundle.isReferenceable());

        // child nodes (list of uuid/name pairs)
        iter = bundle.getChildNodeEntries().iterator();
        while (iter.hasNext()) {
            NodePropBundle.ChildNodeEntry entry = (NodePropBundle.ChildNodeEntry) iter.next();
            writeID(out, entry.getId());  // uuid
            writeQName(out, entry.getName());   // name
        }
        writeID(out, null);

        // write mod count
        writeModCount(out, bundle.getModCount());

        // set size of bundle
        bundle.setSize(out.size()-size);
    }

    /**
     * Deserializes a <code>PropertyState</code> from the data input stream.
     *
     * @param in the input stream
     * @param id the property id for the new propert entry
     * @return the property entry
     * @throws IOException if an I/O error occurs.
     */
    public NodePropBundle.PropertyEntry readPropertyEntry(DataInputStream in, PropertyId id)
            throws IOException {
        NodePropBundle.PropertyEntry entry = new NodePropBundle.PropertyEntry(id);
        // type and modcount
        int type = in.readInt();
        entry.setModCount((short) ((type >> 16) & 0x0ffff));
        type&=0x0ffff;
        entry.setType(type);

        // multiValued
        entry.setMultiValued(in.readBoolean());
        // definitionId
        entry.setPropDefId(PropDefId.valueOf(in.readUTF()));
        // values
        int count = in.readInt();   // count
        InternalValue[] values = new InternalValue[count];
        String[] blobIds = new String[count];
        for (int i = 0; i < count; i++) {
            InternalValue val;
            switch (type) {
                case PropertyType.BINARY:
                    int size = in.readInt();
                    if (InternalValue.USE_DATA_STORE && size == -2) {
                        val = InternalValue.create(dataStore, in.readUTF());
                    } else if (size == -1) {
                        blobIds[i] = in.readUTF();
                        try {
                            if (blobStore instanceof ResourceBasedBLOBStore) {
                                val = InternalValue.create(((ResourceBasedBLOBStore) blobStore).getResource(blobIds[i]));
                            } else {
                                val = InternalValue.create(blobStore.get(blobIds[i]));
                            }
                        } catch (IOException e) {
                            if (errorHandling.ignoreMissingBlobs()) {
                                log.warn("Ignoring error while reading blob-resource: " + e);
                                val = InternalValue.create(new byte[0]);
                            } else {
                                throw e;
                            }
                        } catch (Exception e) {
                            throw new IOException("Unable to create property value: " + e.toString());
                        }
                    } else {
                        // short values into memory
                        byte[] data = new byte[size];
                        in.readFully(data);
                        val = InternalValue.create(data);
                    }
                    break;
                case PropertyType.DOUBLE:
                    val = InternalValue.create(in.readDouble());
                    break;
                case PropertyType.LONG:
                    val = InternalValue.create(in.readLong());
                    break;
                case PropertyType.BOOLEAN:
                    val = InternalValue.create(in.readBoolean());
                    break;
                case PropertyType.NAME:
                    val = InternalValue.create(readQName(in));
                    break;
                case PropertyType.REFERENCE:
                    val = InternalValue.create(readUUID(in));
                    break;
                default:
                    // because writeUTF(String) has a size limit of 64k,
                    // Strings are serialized as <length><byte[]>
                    int len = in.readInt();
                    byte[] bytes = new byte[len];
                    int pos = 0;
                    while (pos<len) {
                        pos+= in.read(bytes, pos, len-pos);
                    }
                    val = InternalValue.valueOf(new String(bytes, "UTF-8"), type);
            }
            values[i] = val;
        }
        entry.setValues(values);
        entry.setBlobIds(blobIds);

        return entry;
    }

    /**
     * Checks a <code>PropertyState</code> from the data input stream.
     *
     * @param in the input stream
     * @return <code>true</code> if the data is valid;
     *         <code>false</code> otherwise.
     */
    public boolean checkPropertyState(DataInputStream in) {
        int type;
        try {
            type = in.readInt();
            short modCount = (short) ((type >> 16) | 0xffff);
            type &= 0xffff;
            log.info("  PropertyType: " + PropertyType.nameFromValue(type));
            log.info("  ModCount: " + modCount);
        } catch (IOException e) {
            log.error("Error while reading property type: " + e);
            return false;
        }
        try {
            boolean isMV = in.readBoolean();
            log.info("  MultiValued: " + isMV);
        } catch (IOException e) {
            log.error("Error while reading multivalued: " + e);
            return false;
        }
        try {
            String defintionId = in.readUTF();
            log.info("  DefinitionId: " + defintionId);
        } catch (IOException e) {
            log.error("Error while reading definition id: " + e);
            return false;
        }

        int count;
        try {
            count = in.readInt();
            log.info("  num values: " + count);
        } catch (IOException e) {
            log.error("Error while reading number of values: " + e);
            return false;
        }
        for (int i = 0; i < count; i++) {
            switch (type) {
                case PropertyType.BINARY:
                    int size;
                    try {
                        size = in.readInt();
                        log.info("  binary size: " + size);
                    } catch (IOException e) {
                        log.error("Error while reading size of binary: " + e);
                        return false;
                    }
                    if (InternalValue.USE_DATA_STORE && size == -2) {
                        try {
                            String s = in.readUTF();
                            log.info("  global data store id: " + s);
                        } catch (IOException e) {
                            log.error("Error while reading blob id: " + e);
                            return false;
                        }
                    } else if (size == -1) {
                        try {
                            String s = in.readUTF();
                            log.info("  blobid: " + s);
                        } catch (IOException e) {
                            log.error("Error while reading blob id: " + e);
                            return false;
                        }
                    } else {
                        // short values into memory
                        byte[] data = new byte[size];
                        try {
                            in.readFully(data);
                            log.info("  binary: " + data.length + " bytes");
                        } catch (IOException e) {
                            log.error("Error while reading inlined binary: " + e);
                            return false;
                        }
                    }
                    break;
                case PropertyType.DOUBLE:
                    try {
                        double d = in.readDouble();
                        log.info("  double: " + d);
                    } catch (IOException e) {
                        log.error("Error while reading double value: " + e);
                        return false;
                    }
                    break;
                case PropertyType.LONG:
                    try {
                        double l = in.readLong();
                        log.info("  long: " + l);
                    } catch (IOException e) {
                        log.error("Error while reading long value: " + e);
                        return false;
                    }
                    break;
                case PropertyType.BOOLEAN:
                    try {
                        boolean b = in.readBoolean();
                        log.info("  boolean: " + b);
                    } catch (IOException e) {
                        log.error("Error while reading boolean value: " + e);
                        return false;
                    }
                    break;
                case PropertyType.NAME:
                    try {
                        QName name = readQName(in);
                        log.info("  name: " + name);
                    } catch (IOException e) {
                        log.error("Error while reading name value: " + e);
                        return false;
                    }
                    break;
                case PropertyType.REFERENCE:
                    try {
                        UUID uuid = readUUID(in);
                        log.info("  reference: " + uuid);
                    } catch (IOException e) {
                        log.error("Error while reading reference value: " + e);
                        return false;
                    }
                    break;
                default:
                    // because writeUTF(String) has a size limit of 64k,
                    // Strings are serialized as <length><byte[]>
                    int len;
                    try {
                        len = in.readInt();
                        log.info("  size of string value: " + len);
                    } catch (IOException e) {
                        log.error("Error while reading size of string value: " + e);
                        return false;
                    }
                    try {
                        byte[] bytes = new byte[len];
                        int pos = 0;
                        while (pos<len) {
                            pos+= in.read(bytes, pos, len-pos);
                        }
                        log.info("  string: " + new String(bytes, "UTF-8"));
                    } catch (IOException e) {
                        log.error("Error while reading string value: " + e);
                        return false;
                    }
            }
        }
        return true;
    }


    /**
     * Serializes a <code>PropertyState</code> to the data output stream
     *
     * @param out the output stream
     * @param state the property entry to store
     * @throws IOException if an I/O error occurs.
     */
    public void writeState(DataOutputStream out, NodePropBundle.PropertyEntry state)
            throws IOException {
        // type & mod count
        out.writeInt(state.getType() | (state.getModCount() << 16));
        // multiValued
        out.writeBoolean(state.isMultiValued());
        // definitionId
        out.writeUTF(state.getPropDefId().toString());
        // values
        InternalValue[] values = state.getValues();
        out.writeInt(values.length); // count
        for (int i = 0; i < values.length; i++) {
            InternalValue val = values[i];
            switch (state.getType()) {
                case PropertyType.BINARY:
                    if(InternalValue.USE_DATA_STORE) {
                        out.writeInt(-2);
                        out.writeUTF(val.toString());
                        break;
                    }
                    // special handling required for binary value:
                    // spool binary value to file in blob store
                    BLOBFileValue blobVal = val.getBLOBFileValue();
                    long size = blobVal.getLength();
                    if (size < 0) {
                        log.warn("Blob has negative size. Potential loss of data. " +
                            "id={} idx={}", state.getId(), String.valueOf(i));
                        out.writeInt(0);
                        values[i] = InternalValue.create(new byte[0]);
                        blobVal.discard();
                    } else if (size > minBlobSize) {
                        out.writeInt(-1);
                        String blobId = state.getBlobId(i);
                        if (blobId == null) {
                            try {
                                InputStream in = blobVal.getStream();
                                try {
                                    blobId = blobStore.createId(state.getId(), i);
                                    blobStore.put(blobId, in, size);
                                    state.setBlobId(blobId, i);
                                } finally {
                                    try {
                                        in.close();
                                    } catch (IOException e) {
                                        // ignore
                                    }
                                }
                            } catch (Exception e) {
                                String msg = "Error while storing blob. id="
                                        + state.getId() + " idx=" + i + " size=" + size;
                                log.error(msg, e);
                                throw new IOException(msg);
                            }
                            try {
                                // replace value instance with value
                                // backed by resource in blob store and delete temp file
                                if (blobStore instanceof ResourceBasedBLOBStore) {
                                    values[i] = InternalValue.create(((ResourceBasedBLOBStore) blobStore).getResource(blobId));
                                } else {
                                    values[i] = InternalValue.create(blobStore.get(blobId));
                                }
                            } catch (Exception e) {
                                log.error("Error while reloading blob. truncating. id=" + state.getId() +
                                        " idx=" + i + " size=" + size, e);
                                values[i] = InternalValue.create(new byte[0]);
                            }
                            blobVal.discard();
                        }
                        // store id of blob as property value
                        out.writeUTF(blobId);   // value
                    } else {
                        // delete evt. blob
                        out.writeInt((int) size);
                        byte[] data = new byte[(int) size];
                        try {
                            InputStream in = blobVal.getStream();
                            try {
                                int pos = 0;
                                while (pos < size) {
                                    int n = in.read(data, pos, (int) size-pos);
                                    if (n < 0) {
                                        throw new EOFException();
                                    }
                                    pos += n;
                                }
                            } finally {
                                try {
                                    in.close();
                                } catch (IOException e) {
                                    // ignore
                                }
                            }
                        } catch (Exception e) {
                            String msg = "Error while storing blob. id="
                                    + state.getId() + " idx=" + i + " size=" + size;
                            log.error(msg, e);
                            throw new IOException(msg);
                        }
                        out.write(data, 0, data.length);
                        // replace value instance with value
                        // backed by resource in blob store and delete temp file
                        values[i] = InternalValue.create(data);
                        blobVal.discard();
                    }
                    break;
                case PropertyType.DOUBLE:
                    out.writeDouble(val.getDouble());
                    break;
                case PropertyType.LONG:
                    out.writeLong(val.getLong());
                    break;
                case PropertyType.BOOLEAN:
                    out.writeBoolean(val.getBoolean());
                    break;
                case PropertyType.NAME:
                    writeQName(out, val.getQName());
                    break;
                case PropertyType.REFERENCE:
                    writeUUID(out, val.getUUID());
                    break;
                default:
                    // because writeUTF(String) has a size limit of 64k,
                    // we're using write(byte[]) instead
                    byte[] bytes = val.toString().getBytes("UTF-8");
                    out.writeInt(bytes.length); // lenght of byte[]
                    out.write(bytes);   // byte[]
            }
        }
    }
}
