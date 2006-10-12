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
package org.apache.jackrabbit.jcr2spi;

import org.apache.jackrabbit.jcr2spi.state.PropertyState;
import org.apache.jackrabbit.jcr2spi.operation.SetPropertyValue;
import org.apache.jackrabbit.jcr2spi.operation.Operation;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.NameFormat;
import org.apache.jackrabbit.value.QValue;
import org.apache.jackrabbit.value.ValueFormat;
import org.apache.jackrabbit.value.ValueHelper;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.PropertyDefinition;
import javax.jcr.lock.LockException;
import javax.jcr.version.VersionException;
import javax.jcr.Property;
import javax.jcr.Item;
import javax.jcr.RepositoryException;
import javax.jcr.Node;
import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.ItemVisitor;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.PropertyType;
import java.io.InputStream;
import java.util.Calendar;

/**
 * <code>PropertyImpl</code>...
 */
public class PropertyImpl extends ItemImpl implements Property {

    private static Logger log = LoggerFactory.getLogger(PropertyImpl.class);

    public static final int UNDEFINED_PROPERTY_LENGTH = -1;

    private final PropertyDefinition definition;

    public PropertyImpl(ItemManagerImpl itemManager, SessionImpl session,
                        PropertyState state, PropertyDefinition definition,
                        ItemLifeCycleListener[] listeners) {
        super(itemManager, session, state, listeners);
        this.definition = definition;
        // value will be read on demand
    }

    //-----------------------------------------------------< Item interface >---
    /**
     * @see Item#getName()
     */
    public String getName() throws RepositoryException {
        checkStatus();
        QName name = getQName();
        try {
            return NameFormat.format(name, session.getNamespaceResolver());
        } catch (NoPrefixDeclaredException npde) {
            // should never get here...
            String msg = "Internal error: encountered unregistered namespace " + name.getNamespaceURI();
            log.debug(msg);
            throw new RepositoryException(msg, npde);
        }
    }

    /**
     * @see Item#getParent()
     */
    public Node getParent() throws ItemNotFoundException, AccessDeniedException, RepositoryException {
        checkStatus();
        return (Node) itemMgr.getItem(getItemState().getParent());
    }

    /**
     * Implementation of {@link Item#accept(javax.jcr.ItemVisitor)} for property.
     *
     * @param visitor
     * @see Item#accept(javax.jcr.ItemVisitor)
     */
    public void accept(ItemVisitor visitor) throws RepositoryException {
        checkStatus();
        visitor.visit(this);
    }

    /**
     * Returns false
     *
     * @return false
     * @see javax.jcr.Item#isNode()
     */
    public boolean isNode() {
	return false;
    }

    //-------------------------------------------------< Property interface >---
    /**
     * @see Property#setValue(javax.jcr.Value)
     */
    public void setValue(Value value) throws ValueFormatException, VersionException, LockException, RepositoryException {
        checkIsWritable(false);
        int valueType = (value != null) ? value.getType() : PropertyType.UNDEFINED;
        int reqType = getRequiredType(valueType);
        setValue(value, reqType);
    }

    /**
     * @see Property#setValue(javax.jcr.Value[])
     */
    public void setValue(Value[] values) throws ValueFormatException, VersionException, LockException, RepositoryException {
        checkIsWritable(true);
        // assert equal types for all values entries
        int valueType = PropertyType.UNDEFINED;
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    // skip null values as those will be purged later
                    continue;
                }
                if (valueType == PropertyType.UNDEFINED) {
                    valueType = values[i].getType();
                } else if (valueType != values[i].getType()) {
                    String msg = "Inhomogeneous type of values (" + safeGetJCRPath() + ")";
                    log.debug(msg);
                    throw new ValueFormatException(msg);
                }
            }
        }

        int targetType = definition.getRequiredType();
        if (targetType == PropertyType.UNDEFINED) {
            targetType = (valueType == PropertyType.UNDEFINED) ?  PropertyType.STRING : valueType;
        }
        // convert to internal values of correct type
        QValue[] qValues = null;
        if (values != null) {
            Value[] vs = ValueHelper.convert(values, targetType, session.getValueFactory());
            qValues = ValueFormat.getQValues(vs, session.getNamespaceResolver());
        }
        setInternalValues(qValues, targetType);
    }

    /**
     * @see Property#setValue(String)
     */
    public void setValue(String value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        checkIsWritable(false);
        int reqType = getRequiredType(PropertyType.STRING);
        if (value == null) {
            setInternalValues(null, reqType);
        } else {
            setValue(session.getValueFactory().createValue(value), reqType);
        }
    }

    /**
     * @see Property#setValue(String[])
     */
    public void setValue(String[] values) throws ValueFormatException, VersionException, LockException, RepositoryException {
        checkIsWritable(true);
        int reqType = getRequiredType(PropertyType.STRING);

        QValue[] qValues = null;
        // convert to internal values of correct type
        if (values != null) {
            qValues = new QValue[values.length];
            for (int i = 0; i < values.length; i++) {
                String string = values[i];
                QValue qValue = null;
                if (string != null) {
                    if (reqType != PropertyType.STRING) {
                        // type conversion required
                        Value v = ValueHelper.convert(string, reqType, session.getValueFactory());
                        qValue = ValueFormat.getQValue(v, session.getNamespaceResolver());
                    } else {
                        // no type conversion required
                        qValue = QValue.create(string);
                    }
                }
                qValues[i] = qValue;
            }
        }
        setInternalValues(qValues, reqType);
    }

    /**
     * @see Property#setValue(InputStream)
     */
    public void setValue(InputStream value) throws ValueFormatException, VersionException, LockException, RepositoryException {
        checkIsWritable(false);
        int reqType = getRequiredType(PropertyType.BINARY);
        setValue(session.getValueFactory().createValue(value), reqType);
    }

    /**
     * @see Property#setValue(long)
     */
    public void setValue(long value) throws ValueFormatException, VersionException, LockException, RepositoryException {
	checkIsWritable(false);
        int reqType = getRequiredType(PropertyType.LONG);
        setValue(session.getValueFactory().createValue(value), reqType);
    }

    /**
     * @see Property#setValue(double)
     */
    public void setValue(double value) throws ValueFormatException, VersionException, LockException, RepositoryException {
    	checkIsWritable(false);
        int reqType = getRequiredType(PropertyType.DOUBLE);
        setValue(session.getValueFactory().createValue(value), reqType);
    }

    /**
     * @see Property#setValue(Calendar)
     */
    public void setValue(Calendar value) throws ValueFormatException, VersionException, LockException, RepositoryException {
	checkIsWritable(false);
        int reqType = getRequiredType(PropertyType.DATE);
        if (value == null) {
            setInternalValues(null, reqType);
        } else {
            setValue(session.getValueFactory().createValue(value), reqType);
        }
    }

    /**
     * @see Property#setValue(boolean)
     */
    public void setValue(boolean value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
    	checkIsWritable(false);
        int reqType = getRequiredType(PropertyType.BOOLEAN);
        setValue(session.getValueFactory().createValue(value), reqType);
    }

    /**
     * @see Property#setValue(Node)
     */
    public void setValue(Node value) throws ValueFormatException, VersionException, LockException, RepositoryException {
	checkIsWritable(false);
        int reqType = getRequiredType(PropertyType.REFERENCE);
        if (value == null) {
            setInternalValues(null, reqType);
        } else {
            if (reqType == PropertyType.REFERENCE) {
                if (value instanceof NodeImpl) {
                    NodeImpl targetNode = (NodeImpl)value;
                    if (targetNode.isNodeType(QName.MIX_REFERENCEABLE)) {
                        QValue qValue = QValue.create(targetNode.getUUID(), PropertyType.REFERENCE);
                        setInternalValues(new QValue[]{qValue}, reqType);
                    } else {
                        throw new ValueFormatException("Target node must be of node type mix:referenceable");
                    }
                } else {
                    String msg = "Incompatible Node object: " + value + "(" + safeGetJCRPath() + ")";
                    log.debug(msg);
                    throw new RepositoryException(msg);
                }
            } else {
                throw new ValueFormatException("Property must be of type REFERENCE (" + safeGetJCRPath() + ")");
            }
        }
    }

    /**
     * @see Property#getValue()
     */
    public Value getValue() throws ValueFormatException, RepositoryException {
        QValue value = getQValue();
        return ValueFormat.getJCRValue(value, session.getNamespaceResolver(), session.getValueFactory());
    }

    /**
     * @see Property#getValues()
     */
    public Value[] getValues() throws ValueFormatException, RepositoryException {
        QValue[] qValues = getQValues();
        Value[] values = new Value[qValues.length];
        for (int i = 0; i < qValues.length; i++) {
            values[i] = ValueFormat.getJCRValue(qValues[i], session.getNamespaceResolver(), session.getValueFactory());
        }
        return values;
    }

    /**
     * @see Property#getString()
     */
    public String getString() throws ValueFormatException, RepositoryException {
        return getValue().getString();
    }

    /**
     * @see Property#getStream()
     */
    public InputStream getStream() throws ValueFormatException, RepositoryException {
        return getValue().getStream();
    }

    /**
     * @see Property#getLong()
     */
    public long getLong() throws ValueFormatException, RepositoryException {
        return getValue().getLong();
    }

    /**
     * @see Property#getDouble()
     */
    public double getDouble() throws ValueFormatException, RepositoryException {
        return getValue().getDouble();
    }

    /**
     * @see Property#getDate()
     */
    public Calendar getDate() throws ValueFormatException, RepositoryException {
        return getValue().getDate();
    }

    /**
     * @see Property#getBoolean()
     */
    public boolean getBoolean() throws ValueFormatException, RepositoryException {
        return getValue().getBoolean();
    }

    /**
     * @see Property#getNode()
     */
    public Node getNode() throws ValueFormatException, RepositoryException {
        QValue value = getQValue();
        if (value.getType() == PropertyType.REFERENCE) {
            return session.getNodeByUUID(value.getString());
        } else {
            throw new ValueFormatException("Property must be of type REFERENCE (" + safeGetJCRPath() + ")");
        }
    }

    /**
     * @see Property#getLength
     */
    public long getLength() throws ValueFormatException, RepositoryException {
        return getLength(getQValue());
    }

    /**
     * @see Property#getLengths
     */
    public long[] getLengths() throws ValueFormatException, RepositoryException {
        QValue[] values = getQValues();
        long[] lengths = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            lengths[i] = getLength(values[i]);
        }
        return lengths;
    }

    /**
     *
     * @param value
     * @return
     * @throws RepositoryException
     */
    private long getLength(QValue value) throws RepositoryException {
        long length = UNDEFINED_PROPERTY_LENGTH;
        switch (value.getType()) {
            case PropertyType.STRING:
            case PropertyType.BINARY:
            case PropertyType.LONG:
            case PropertyType.DOUBLE:
                length = value.getLength();
                break;
            case PropertyType.NAME:
                Value jcrValue = ValueFormat.getJCRValue(value, session.getNamespaceResolver(), session.getValueFactory());
                length = jcrValue.getString().length();
                break;
        }
        return length;
    }

    /**
     * @see javax.jcr.Property#getDefinition()
     */
    public PropertyDefinition getDefinition() throws RepositoryException {
	checkStatus();
        return definition;
    }

    /**
     * @see javax.jcr.Property#getType()
     */
    public int getType() throws RepositoryException {
	checkStatus();
	return getPropertyState().getType();
    }

    //-----------------------------------------------------------< ItemImpl >---
    /**
     * Returns the QName defined with this <code>PropertyState</code>
     *
     * @return
     * @see PropertyState#getQName()
     * @see ItemImpl#getQName()
     */
    QName getQName() {
        return getPropertyState().getQName();
    }

    //------------------------------------------------------< check methods >---
    /**
     *
     * @param multiValues
     * @throws RepositoryException
     */
    private void checkIsWritable(boolean multiValues) throws RepositoryException {
        // check common to properties and nodes
        checkIsWritable();

        // property specific check
        if (definition.isMultiple() != multiValues) {
            throw new ValueFormatException(getPath() + "Multivalue definition of " + safeGetJCRPath() + " does not match to given value(s).");
        }
    }

    //---------------------------------------------< private implementation >---
    /**
     *
     * @return true if the definition indicates that this Property is multivalued.
     */
    private boolean isMultiple() {
	return definition.isMultiple();
    }

    /**
     *
     * @param defaultType
     * @return the required type for this property.
     */
    private int getRequiredType(int defaultType) {
        // check type according to definition of this property
        int reqType = definition.getRequiredType();
        if (reqType == PropertyType.UNDEFINED) {
            if (defaultType == PropertyType.UNDEFINED) {
                reqType = PropertyType.STRING;
            } else {
                reqType = defaultType;
            }
        }
        return reqType;
    }

    /**
     *
     * @return
     * @throws ValueFormatException
     * @throws RepositoryException
     */
    private QValue getQValue() throws ValueFormatException, RepositoryException {
        checkStatus();
        if (isMultiple()) {
            throw new ValueFormatException(safeGetJCRPath() + " is multi-valued and can therefore only be retrieved as an array of values");
        }
        // avoid unnecessary object creation if possible
        return getPropertyState().getValue();
    }

    /**
     *
     * @return
     * @throws ValueFormatException
     * @throws RepositoryException
     */
    private QValue[] getQValues() throws ValueFormatException, RepositoryException {
        checkStatus();
        if (!isMultiple()) {
            throw new ValueFormatException(safeGetJCRPath() + " is not multi-valued and can therefore only be retrieved as single value");
        }
        // avoid unnecessary object creation if possible
        return getPropertyState().getValues();
    }

    /**
     *
     * @param value
     * @param requiredType
     * @throws RepositoryException
     */
    private void setValue(Value value, int requiredType) throws RepositoryException {
        if (requiredType == PropertyType.UNDEFINED) {
            // should never get here since calling methods assert valid type
            throw new IllegalArgumentException("Property type of a value cannot be undefined (" + safeGetJCRPath() + ").");
        }
        if (value == null) {
            setInternalValues(null, requiredType);
            return;
        }

        QValue qValue;
        if (requiredType != value.getType()) {
            // type conversion required
            Value v = ValueHelper.convert(value, requiredType, session.getValueFactory());
            qValue = ValueFormat.getQValue(v, session.getNamespaceResolver());
        } else {
            // no type conversion required
            qValue = ValueFormat.getQValue(value, session.getNamespaceResolver());
        }
        setInternalValues(new QValue[]{qValue}, requiredType);
    }

    /**
     *
     * @param qValues
     * @param valueType
     * @throws ConstraintViolationException
     * @throws RepositoryException
     */
    private void setInternalValues(QValue[] qValues, int valueType) throws ConstraintViolationException, RepositoryException {
        // check for null value
        if (qValues == null) {
            // setting a property to null removes it automatically
            remove();
            return;
        }
        // modify the state of this property
        Operation op = SetPropertyValue.create(getPropertyState(), qValues, valueType);
        session.getSessionItemStateManager().execute(op);
    }

    /**
     * Private helper to access the <code>PropertyState</code> directly
     *
     * @return state for this Property
     */
    private PropertyState getPropertyState() {
        return (PropertyState) getItemState();
    }
}
