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
package org.apache.jackrabbit.core.state;

import org.apache.jackrabbit.core.PropertyId;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.ItemId;
import org.apache.jackrabbit.core.nodetype.PropDefId;
import org.apache.jackrabbit.core.value.BLOBFileValue;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.name.QName;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * <code>PropertyState</code> represents the state of a <code>Property</code>.
 */
public class PropertyState extends ItemState {

    /**
     * Serialization UID of this class.
     */
    static final long serialVersionUID = 4569719974514326906L;

    /**
     * the id of this property state
     */
    private PropertyId id;

    /**
     * the internal values
     */
    private InternalValue[] values;

    /**
     * the type of this property state
     */
    private int type;

    /**
     * flag indicating if this is a multivalue property
     */
    private boolean multiValued;

    /**
     * the property definition id
     */
    private PropDefId defId;

    /**
     * Constructs a new property state that is initially connected to an
     * overlayed state.
     *
     * @param overlayedState the backing property state being overlayed
     * @param initialStatus  the initial status of the property state object
     * @param isTransient    flag indicating whether this state is transient or not
     */
    public PropertyState(PropertyState overlayedState, int initialStatus,
                         boolean isTransient) {
        super(overlayedState, initialStatus, isTransient);
        pull();
    }

    /**
     * Create a new <code>PropertyState</code>
     *
     * @param id            id of the property
     * @param initialStatus the initial status of the property state object
     * @param isTransient   flag indicating whether this state is transient or not
     */
    public PropertyState(PropertyId id, int initialStatus, boolean isTransient) {
        super(initialStatus, isTransient);
        this.id = id;
        type = PropertyType.UNDEFINED;
        values = InternalValue.EMPTY_ARRAY;
        multiValued = false;
    }

    /**
     * {@inheritDoc}
     */
    protected synchronized void copy(ItemState state) {
        synchronized (state) {
            PropertyState propState = (PropertyState) state;
            id = propState.id;
            type = propState.type;
            defId = propState.defId;
            values = propState.values;
            multiValued = propState.multiValued;
        }
    }

    //-------------------------------------------------------< public methods >
    /**
     * Determines if this item state represents a node.
     *
     * @return always false
     * @see ItemState#isNode
     */
    public boolean isNode() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public ItemId getId() {
        return id;
    }

    /**
     * Returns the id of this property state.
     * @return the id of this property state.
     */
    public PropertyId getPropertyId() {
        return id;
    }

    /**
     * {@inheritDoc}
     */
    public NodeId getParentId() {
        return id.getParentId();
    }

    /**
     * Returns the name of this property.
     *
     * @return the name of this property.
     */
    public QName getName() {
        return id.getName();
    }

    /**
     * Sets the type of this property.
     *
     * @param type the type to be set
     * @see PropertyType
     */
    public void setType(int type) {
        this.type = type;
    }

    /**
     * Sets the flag indicating whether this property is multi-valued.
     *
     * @param multiValued flag indicating whether this property is multi-valued
     */
    public void setMultiValued(boolean multiValued) {
        this.multiValued = multiValued;
    }

    /**
     * Returns the type of this property.
     *
     * @return the type of this property.
     * @see PropertyType
     */
    public int getType() {
        return type;
    }

    /**
     * Returns true if this property is multi-valued, otherwise false.
     *
     * @return true if this property is multi-valued, otherwise false.
     */
    public boolean isMultiValued() {
        return multiValued;
    }

    /**
     * Returns the id of the definition applicable to this property state.
     *
     * @return the id of the definition
     */
    public PropDefId getDefinitionId() {
        return defId;
    }

    /**
     * Sets the id of the definition applicable to this property state.
     *
     * @param defId the id of the definition
     */
    public void setDefinitionId(PropDefId defId) {
        this.defId = defId;
    }

    /**
     * Sets the value(s) of this property.
     *
     * @param values the new values
     */
    public void setValues(InternalValue[] values) {
        this.values = values;
    }

    /**
     * Returns the value(s) of this property.
     *
     * @return the value(s) of this property.
     */
    public InternalValue[] getValues() {
        return values;
    }

    //-------------------------------------------------< Serializable support >
    private void writeObject(ObjectOutputStream out) throws IOException {
        // important: fields must be written in same order as they are
        // read in readObject(ObjectInputStream)
        out.writeUTF(getName().toString());
        out.writeInt(type);
        out.writeBoolean(multiValued);
        if (values == null) {
            out.writeShort(-1);
        } else {
            out.writeShort(values.length);
            for (int i = 0; i < values.length; i++) {
                InternalValue val = values[i];
                try {
                    if (type == PropertyType.BINARY) {
                        // special handling required for binary value
                        BLOBFileValue blob = (BLOBFileValue) val.internalValue();
                        InputStream in = blob.getStream();
                        out.writeLong(blob.getLength());
                        byte[] buf = new byte[0x2000];
                        try {
                            int read;
                            while ((read = in.read(buf)) > 0) {
                                out.write(buf, 0, read);
                            }
                        } finally {
                            in.close();
                        }
                    } else {
                        out.writeUTF(val.toString());
                    }
                } catch (IllegalStateException ise) {
                    throw new IOException(ise.getMessage());
                } catch (RepositoryException re) {
                    throw new IOException(re.getMessage());
                }
            }
        }
    }

    private void readObject(ObjectInputStream in) throws IOException {
        // important: fields must be read in same order as they are
        // written in writeObject(ObjectOutputStream)
        QName name = QName.valueOf(in.readUTF());
        type = in.readInt();
        multiValued = in.readBoolean();
        short count = in.readShort(); // # of values
        if (count < 0) {
            values = null;
        } else {
            values = new InternalValue[count];
            for (int i = 0; i < values.length; i++) {
                if (type == PropertyType.BINARY) {
                    // special handling required for binary value
                    final long length = in.readLong();
                    final InputStream stream = in;
                    // create InputStream wrapper of size 'length'
                    values[i] = InternalValue.create(new InputStream() {

                        long consumed = 0;

                        public int read() throws IOException {
                            if (consumed >= length) {
                                return -1;  // eof
                            }
                            int b = stream.read();
                            consumed++;
                            return b;
                        }

                        public int read(byte b[], int off, int len) throws IOException {
                            if (consumed >= length) {
                                return -1;  // eof
                            }
                            if ((consumed + len) > length) {
                                len = (int) (length - consumed);
                            }
                            int read = super.read(b, off, len);
                            consumed += read;
                            return read;
                        }

                        public long skip(long n) throws IOException {
                            if (consumed >= length && n > 0) {
                                return -1;  // eof
                            }
                            if ((consumed + n) > length) {
                                n = length - consumed;
                            }
                            long skipped = super.skip(n);
                            consumed += skipped;
                            return skipped;
                        }

                        public void close() {
                            // nop
                        }
                    });
                } else {
                    values[i] = InternalValue.valueOf(in.readUTF(), type);
                }
            }
        }
    }
}
