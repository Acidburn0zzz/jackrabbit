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
package org.apache.jackrabbit.rmi.client;

import java.io.InputStream;
import java.rmi.RemoteException;
import java.util.Calendar;

import javax.jcr.BinaryValue;
import javax.jcr.BooleanValue;
import javax.jcr.DateValue;
import javax.jcr.DoubleValue;
import javax.jcr.ItemVisitor;
import javax.jcr.LongValue;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.ReferenceValue;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.StringValue;
import javax.jcr.Value;
import javax.jcr.nodetype.PropertyDefinition;

import org.apache.jackrabbit.rmi.remote.RemoteProperty;
import org.apache.jackrabbit.rmi.remote.SerialValue;

/**
 * Local adapter for the JCR-RMI
 * {@link org.apache.jackrabbit.rmi.remote.RemoteProperty RemoteProperty}
 * inteface. This class makes a remote property locally available using
 * the JCR {@link javax.jcr.Property Property} interface.
 *
 * @author Jukka Zitting
 * @see javax.jcr.Property
 * @see org.apache.jackrabbit.rmi.remote.RemoteProperty
 */
public class ClientProperty extends ClientItem implements Property {

    /** The adapted remote property. */
    private RemoteProperty remote;

    /**
     * Creates a local adapter for the given remote property.
     *
     * @param session current session
     * @param remote  remote property
     * @param factory local adapter factory
     */
    public ClientProperty(
            Session session, RemoteProperty remote,
            LocalAdapterFactory factory) {
        super(session, remote, factory);
        this.remote = remote;
    }

    /**
     * Calls the {@link ItemVisitor#visit(Property) ItemVisitor.visit(Property}
     * method of the given visitor. Does not contact the remote property, but
     * the visitor may invoke other methods that do contact the remote property.
     *
     * {@inheritDoc}
     */
    public void accept(ItemVisitor visitor) throws RepositoryException {
        visitor.visit(this);
    }

    /**
     * Returns the boolean value of this property. Implemented as
     * getValue().getBoolean().
     *
     * {@inheritDoc}
     */
    public boolean getBoolean() throws RepositoryException {
        return getValue().getBoolean();
    }

    /**
     * Returns the date value of this property. Implemented as
     * getValue().getDate().
     *
     * {@inheritDoc}
     */
    public Calendar getDate() throws RepositoryException {
        return getValue().getDate();
    }

    /**
     * Returns the double value of this property. Implemented as
     * getValue().getDouble().
     *
     * {@inheritDoc}
     */
    public double getDouble() throws RepositoryException {
        return getValue().getDouble();
    }

    /**
     * Returns the long value of this property. Implemented as
     * getValue().getLong().
     *
     * {@inheritDoc}
     */
    public long getLong() throws RepositoryException {
        return getValue().getLong();
    }

    /**
     * Returns the binary value of this property. Implemented as
     * getValue().getStream().
     *
     * {@inheritDoc}
     */
    public InputStream getStream() throws RepositoryException {
        return getValue().getStream();
    }

    /**
     * Returns the string value of this property. Implemented as
     * getValue().getString().
     *
     * {@inheritDoc}
     */
    public String getString() throws RepositoryException {
        return getValue().getString();
    }

    /** {@inheritDoc} */
    public Value getValue() throws RepositoryException {
        try {
            return remote.getValue();
        } catch (RemoteException ex) {
            throw new RemoteRepositoryException(ex);
        }
    }

    /** {@inheritDoc} */
    public Value[] getValues() throws RepositoryException {
        try {
            return remote.getValues();
        } catch (RemoteException ex) {
            throw new RemoteRepositoryException(ex);
        }
    }

    /**
     * Sets the boolean value of this property. Implemented as
     * setValue(new BooleanValue(value)).
     *
     * {@inheritDoc}
     */
    public void setValue(boolean value) throws RepositoryException {
        setValue(new BooleanValue(value));
    }

    /**
     * Sets the date value of this property. Implemented as
     * setValue(new DateValue(value)).
     *
     * {@inheritDoc}
     */
    public void setValue(Calendar value) throws RepositoryException {
        setValue(new DateValue(value));
    }

    /**
     * Sets the double value of this property. Implemented as
     * setValue(new DoubleValue(value)).
     *
     * {@inheritDoc}
     */
    public void setValue(double value) throws RepositoryException {
        setValue(new DoubleValue(value));
    }

    /**
     * Sets the binary value of this property. Implemented as
     * setValue(new BinaryValue(value)).
     *
     * {@inheritDoc}
     */
    public void setValue(InputStream value) throws RepositoryException {
        setValue(new BinaryValue(value));
    }

    /**
     * Sets the long value of this property. Implemented as
     * setValue(new LongValue(value)).
     *
     * {@inheritDoc}
     */
    public void setValue(long value) throws RepositoryException {
        setValue(new LongValue(value));
    }

    /**
     * Sets the reference value of this property. Implemented as
     * setValue(new ReferenceValue(value)).
     *
     * {@inheritDoc}
     */
    public void setValue(Node value) throws RepositoryException {
        setValue(new ReferenceValue(value));
    }

    /**
     * Sets the string value of this property. Implemented as
     * setValue(new StringValue(value)).
     *
     * {@inheritDoc}
     */
    public void setValue(String value) throws RepositoryException {
        setValue(new StringValue(value));
    }

    /**
     * Sets the string values of this property. Implemented as
     * setValue(new Value[] { new StringValue(strings[0]), ... }).
     *
     * {@inheritDoc}
     */
    public void setValue(String[] strings) throws RepositoryException {
        Value[] values = new Value[strings.length];
        for (int i = 0; i < strings.length; i++) {
            values[i] = new StringValue(strings[i]);
        }
        setValue(values);
    }

    /** {@inheritDoc} */
    public void setValue(Value value) throws RepositoryException {
        try {
            remote.setValue(new SerialValue(value));
        } catch (RemoteException ex) {
            throw new RemoteRepositoryException(ex);
        }
    }

    /** {@inheritDoc} */
    public void setValue(Value[] values) throws RepositoryException {
        try {
            remote.setValue(SerialValue.makeSerialValueArray(values));
        } catch (RemoteException ex) {
            throw new RemoteRepositoryException(ex);
        }
    }

    /**
     * Returns the reference value of this property. Implemented by
     * converting the reference value to an UUID string and using the
     * current session to look up the referenced node.
     *
     * {@inheritDoc}
     */
    public Node getNode() throws RepositoryException {
        return getSession().getNodeByUUID(getString());
    }

    /** {@inheritDoc} */
    public long getLength() throws RepositoryException {
        try {
            return remote.getLength();
        } catch (RemoteException ex) {
            throw new RemoteRepositoryException(ex);
        }
    }

    /** {@inheritDoc} */
    public long[] getLengths() throws RepositoryException {
        try {
            return remote.getLengths();
        } catch (RemoteException ex) {
            throw new RemoteRepositoryException(ex);
        }
    }

    /** {@inheritDoc} */
    public PropertyDefinition getDefinition() throws RepositoryException {
        try {
            return getFactory().getPropertyDef(remote.getDefinition());
        } catch (RemoteException ex) {
            throw new RemoteRepositoryException(ex);
        }
    }

    /** {@inheritDoc} */
    public int getType() throws RepositoryException {
        try {
            return remote.getType();
        } catch (RemoteException ex) {
            throw new RemoteRepositoryException(ex);
        }
    }

}
