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
package org.apache.jackrabbit.core.value;

import org.apache.jackrabbit.core.data.DataStore;
import org.apache.jackrabbit.core.fs.FileSystemResource;
import org.apache.jackrabbit.name.MalformedPathException;
import org.apache.jackrabbit.name.NameException;
import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.NameFormat;
import org.apache.jackrabbit.name.PathFormat;
import org.apache.jackrabbit.util.ISO8601;
import org.apache.jackrabbit.uuid.UUID;
import org.apache.jackrabbit.value.BinaryValue;
import org.apache.jackrabbit.value.BooleanValue;
import org.apache.jackrabbit.value.DateValue;
import org.apache.jackrabbit.value.DoubleValue;
import org.apache.jackrabbit.value.LongValue;
import org.apache.jackrabbit.value.NameValue;
import org.apache.jackrabbit.value.PathValue;
import org.apache.jackrabbit.value.ReferenceValue;
import org.apache.jackrabbit.value.StringValue;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;

/**
 * <code>InternalValue</code> represents the internal format of a property value.
 * <p/>
 * The following table specifies the internal format for every property type:
 * <pre>
 * <table>
 * <tr><b>PropertyType</b><td></td><td><b>Internal Format</b></td></tr>
 * <tr>STRING<td></td><td>String</td></tr>
 * <tr>LONG<td></td><td>Long</td></tr>
 * <tr>DOUBLE<td></td><td>Double</td></tr>
 * <tr>DATE<td></td><td>Calendar</td></tr>
 * <tr>BOOLEAN<td></td><td>Boolean</td></tr>
 * <tr>NAME<td></td><td>QName</td></tr>
 * <tr>PATH<td></td><td>Path</td></tr>
 * <tr>BINARY<td></td><td>BLOBFileValue</td></tr>
 * <tr>REFERENCE<td></td><td>UUID</td></tr>
 * </table>
 * </pre>
 */
public class InternalValue {

    public static final InternalValue[] EMPTY_ARRAY = new InternalValue[0];

    private static final InternalValue BOOLEAN_TRUE = new InternalValue(true);

    private static final InternalValue BOOLEAN_FALSE = new InternalValue(false);
    
    /**
     * If set to 'true', the new global data store will be used, otherwise the old blob store implementation
     */
    public static final boolean USE_DATA_STORE = Boolean.valueOf(System.getProperty("org.jackrabbit.useDataStore", "false")).booleanValue();

    private final Object val;
    private final int type;

    //------------------------------------------------------< factory methods >
    /**
     * @param value
     * @param nsResolver
     * @return
     * @throws ValueFormatException
     * @throws RepositoryException
     */
    public static InternalValue create(Value value, NamespaceResolver nsResolver, DataStore store)
            throws ValueFormatException, RepositoryException {
        return create(value, nsResolver);
    }

    /**
     * @param value
     * @param nsResolver
     * @return
     * @throws ValueFormatException
     * @throws RepositoryException
     */
    public static InternalValue create(Value value, NamespaceResolver nsResolver)
            throws ValueFormatException, RepositoryException {
        if (value == null) {
            throw new IllegalArgumentException("null value");
        }

        switch (value.getType()) {
            case PropertyType.BINARY:
                try {
                    if (value instanceof BLOBFileValue) {
                        return new InternalValue((BLOBFileValue) value);
                    } else {
                        InputStream stream = value.getStream();
                        try {
                            return createTemporary(stream);
                        } finally {
                            try {
                                stream.close();
                            } catch (IOException e) {
                                // ignore
                            }
                        }
                    }
                } catch (IOException ioe) {
                    throw new ValueFormatException(ioe.getMessage());
                }
            case PropertyType.BOOLEAN:
                return create(value.getBoolean());
            case PropertyType.DATE:
                return create(value.getDate());
            case PropertyType.DOUBLE:
                return create(value.getDouble());
            case PropertyType.LONG:
                return create(value.getLong());
            case PropertyType.REFERENCE:
                return create(new UUID(value.getString()));
            case PropertyType.NAME:
                try {
                    return create(NameFormat.parse(value.getString(), nsResolver));
                } catch (NameException e) {
                    throw new ValueFormatException(e.getMessage());
                }
            case PropertyType.PATH:
                try {
                    return create(PathFormat.parse(value.getString(), nsResolver));
                } catch (MalformedPathException mpe) {
                    throw new ValueFormatException(mpe.getMessage());
                }
            case PropertyType.STRING:
                return create(value.getString());

            default:
                throw new IllegalArgumentException("illegal value");
        }
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(String value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(long value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(double value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(Calendar value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(boolean value) {
        return value ? BOOLEAN_TRUE : BOOLEAN_FALSE;
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(byte[] value) {
        return new InternalValue(new BLOBFileValue(value));
    }

    /**
     * @param value
     * @return
     * @throws IOException
     */
    public static InternalValue createTemporary(InputStream value) throws IOException {
        return new InternalValue(new BLOBFileValue(value, true));
    }    

    /**
     * @param value
     * @param temp
     * @return
     * @throws IOException
     */
    public static InternalValue create(InputStream value) throws IOException {
        return new InternalValue(new BLOBFileValue(value, false));
    }

    /**
     * @param value
     * @return
     * @throws IOException
     */
    public static InternalValue create(FileSystemResource value)
            throws IOException {
        return new InternalValue(new BLOBFileValue(value));
    }

    /**
     * @param value
     * @return
     * @throws IOException
     */
    public static InternalValue create(File value) throws IOException {
        return new InternalValue(new BLOBFileValue(value));
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(QName value) {
        return new InternalValue(value);
    }

    /**
     * @param values
     * @return the created value
     */
    public static InternalValue[] create(QName[] values) {
        InternalValue[] ret = new InternalValue[values.length];
        for (int i = 0; i < values.length; i++) {
            ret[i] = new InternalValue(values[i]);
        }
        return ret;
    }
    
    /**
     * @param values
     * @return the created value
     */
    public static InternalValue[] create(String[] values) {
        InternalValue[] ret = new InternalValue[values.length];
        for (int i = 0; i < values.length; i++) {
            ret[i] = new InternalValue(values[i]);
        }
        return ret;
    }    

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(Path value) {
        return new InternalValue(value);
    }

    /**
     * @param value
     * @return the created value
     */
    public static InternalValue create(UUID value) {
        return new InternalValue(value);
    }

    //----------------------------------------------------< conversions, etc. >
    /**
     * @param nsResolver
     * @return
     * @throws RepositoryException
     */
    public Value toJCRValue(NamespaceResolver nsResolver)
            throws RepositoryException {
        switch (type) {
            case PropertyType.BINARY:
                return new BinaryValue(((BLOBFileValue) val).getStream());
            case PropertyType.BOOLEAN:
                return new BooleanValue(((Boolean) val));
            case PropertyType.DATE:
                return new DateValue((Calendar) val);
            case PropertyType.DOUBLE:
                return new DoubleValue((Double) val);
            case PropertyType.LONG:
                return new LongValue((Long) val);
            case PropertyType.REFERENCE:
                return ReferenceValue.valueOf(val.toString());
            case PropertyType.PATH:
                try {
                    return PathValue.valueOf(PathFormat.format((Path) val, nsResolver));
                } catch (NoPrefixDeclaredException npde) {
                    // should never get here...
                    throw new RepositoryException("internal error: encountered unregistered namespace", npde);
                }
            case PropertyType.NAME:
                return NameValue.valueOf((QName) val, nsResolver);
            case PropertyType.STRING:
                return new StringValue((String) val);
            default:
                throw new RepositoryException("illegal internal value type");
        }
    }

    /**
     * @deprecated
     * @return the internal object
     */
    public Object internalValue() {
        return val;
    }
    
    public BLOBFileValue getBLOBFileValue() {
        assert val != null && type == PropertyType.BINARY;
        return (BLOBFileValue) val;
    }
    
    public UUID getUUID() {
        assert val != null && type == PropertyType.REFERENCE;
        return (UUID) val;
    }
    
    public boolean getBoolean() {
        assert val != null && type == PropertyType.BOOLEAN;
        return ((Boolean) val).booleanValue();
    }    

    public QName getQName() {
        assert val != null && type == PropertyType.NAME;
        return (QName) val;
    }
    
    public Path getPath() {
        assert val != null && type == PropertyType.PATH;
        return (Path) val;
    }
    
    public long getLong() {
        assert val != null && type == PropertyType.LONG;
        return ((Long) val).longValue();
    }
    
    public double getDouble() {
        assert val != null && type == PropertyType.DOUBLE;
        return ((Double) val).doubleValue();
    }
    
    public Calendar getDate() {
        assert val != null && type == PropertyType.DATE;
        return (Calendar) val;
    }
    
    public String getString() {
        assert val != null && type == PropertyType.STRING;
        return (String) val;
    }    

    /**
     * @return the type
     */
    public int getType() {
        return type;
    }

    /**
     * @return
     * @throws RepositoryException
     */
    public InternalValue createCopy() throws RepositoryException {
        if (type == PropertyType.BINARY) {
            // return a copy since the wrapped BLOBFileValue instance is mutable
            try {
                InputStream stream = ((BLOBFileValue) val).getStream();
                try {
                    return createTemporary(stream);
                } finally {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            } catch (IOException ioe) {
                throw new RepositoryException("failed to copy binary value", ioe);
            }
        } else {
            // for all other types it's safe to return 'this' because the
            // wrapped value is immutable (and therefore this instance as well)
            return this;
        }
    }

    /**
     * Parses the given string as an <code>InternalValue</code> of the
     * specified type. The string must be in the format returned by the
     * <code>InternalValue.toString()</code> method.
     *
     * @param s a <code>String</code> containing the <code>InternalValue</code>
     *          representation to be parsed.
     * @param type
     * @return the <code>InternalValue</code> represented by the arguments
     * @throws IllegalArgumentException if the specified string can not be parsed
     *                                  as an <code>InternalValue</code> of the
     *                                  specified type.
     * @see #toString()
     */
    public static InternalValue valueOf(String s, int type) {
        switch (type) {
            case PropertyType.BOOLEAN:
                return create(Boolean.valueOf(s).booleanValue());
            case PropertyType.DATE:
                return create(ISO8601.parse(s));
            case PropertyType.DOUBLE:
                return create(Double.valueOf(s).doubleValue());
            case PropertyType.LONG:
                return create(Long.valueOf(s).longValue());
            case PropertyType.REFERENCE:
                return create(new UUID(s));
            case PropertyType.PATH:
                return create(Path.valueOf(s));
            case PropertyType.NAME:
                return create(QName.valueOf(s));
            case PropertyType.STRING:
                return create(s);

            case PropertyType.BINARY:
                throw new IllegalArgumentException(
                        "this method does not support the type PropertyType.BINARY");
            default:
                throw new IllegalArgumentException("illegal type: " + type);
        }
    }

    //-------------------------------------------< java.lang.Object overrides >
    /**
     * Returns the string representation of this internal value.
     *
     * @return string representation of this internal value
     */
    public String toString() {
        if (type == PropertyType.DATE) {
            return ISO8601.format((Calendar) val);
        } else {
            return val.toString();
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof InternalValue) {
            InternalValue other = (InternalValue) obj;
            return val.equals(other.val);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public int hashCode() {
        return val.hashCode();
    }

    //-------------------------------------------------------< implementation >
    private InternalValue(String value) {
        val = value;
        type = PropertyType.STRING;
    }

    private InternalValue(QName value) {
        val = value;
        type = PropertyType.NAME;
    }

    private InternalValue(long value) {
        val = new Long(value);
        type = PropertyType.LONG;
    }

    private InternalValue(double value) {
        val = new Double(value);
        type = PropertyType.DOUBLE;
    }

    private InternalValue(Calendar value) {
        val = value;
        type = PropertyType.DATE;
    }

    private InternalValue(boolean value) {
        val = Boolean.valueOf(value);
        type = PropertyType.BOOLEAN;
    }

    private InternalValue(BLOBFileValue value) {
        val = value;
        type = PropertyType.BINARY;
    }

    private InternalValue(Path value) {
        val = value;
        type = PropertyType.PATH;
    }

    private InternalValue(UUID value) {
        val = value;
        type = PropertyType.REFERENCE;
    }
}
