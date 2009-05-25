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
package org.apache.jackrabbit.value;

import java.io.InputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Calendar;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.ValueFormatException;

/**
 * This class implements the <code>ValueFactory</code> interface.
 *
 * @see javax.jcr.Session#getValueFactory()
 */
public class ValueFactoryImpl implements ValueFactory {

    private static final ValueFactory valueFactory = new ValueFactoryImpl();

    /**
     * Constructs a <code>ValueFactory</code> object.
     */
    protected ValueFactoryImpl() {
    }

    //--------------------------------------------------------------------------
    /**
     *
     */
    public static ValueFactory getInstance() {
        return valueFactory;
    }

    //---------------------------------------------------------< ValueFactory >
    /**
     * {@inheritDoc}
     */
    public Value createValue(boolean value) {
        return new BooleanValue(value);
    }

    /**
     * {@inheritDoc}
     */
    public Value createValue(Calendar value) {
        return new DateValue(value);
    }

    /**
     * {@inheritDoc}
     */
    public Value createValue(double value) {
        return new DoubleValue(value);
    }

    /**
     * {@inheritDoc}
     */
    public Value createValue(InputStream value) {
        return new BinaryValue(value);
    }

    /**
     * {@inheritDoc}
     */
    public Value createValue(long value) {
        return new LongValue(value);
    }

    /**
     * {@inheritDoc}
     */
    public Value createValue(Node value) throws RepositoryException {
        return createValue(value, false);
    }

    /**
     * {@inheritDoc}
     */
    public Value createValue(String value) {
        return new StringValue(value);
    }

    /**
     * {@inheritDoc}
     */
    public Value createValue(String value, int type)
            throws ValueFormatException {
        Value val;
        switch (type) {
            case PropertyType.STRING:
                val = new StringValue(value);
                break;
            case PropertyType.BOOLEAN:
                val = BooleanValue.valueOf(value);
                break;
            case PropertyType.DOUBLE:
                val = DoubleValue.valueOf(value);
                break;
            case PropertyType.LONG:
                val = LongValue.valueOf(value);
                break;
            case PropertyType.DECIMAL:
                val = DecimalValue.valueOf(value);
                break;
            case PropertyType.DATE:
                val = DateValue.valueOf(value);
                break;
            case PropertyType.NAME:
                val = NameValue.valueOf(value);
                break;
            case PropertyType.PATH:
                val = PathValue.valueOf(value);
                break;
            case PropertyType.URI:
                val = URIValue.valueOf(value);
                break;
            case PropertyType.REFERENCE:
                val = ReferenceValue.valueOf(value);
                break;
            case PropertyType.WEAKREFERENCE:
                val = WeakReferenceValue.valueOf(value);
                break;
            case PropertyType.BINARY:
                val = new BinaryValue(value);
                break;
            default:
                throw new IllegalArgumentException("Invalid type constant: " + type);
        }
        return val;
    }

    /**
     * {@inheritDoc}
     */
    public Binary createBinary(InputStream stream) throws RepositoryException {
        try {
            return new BinaryImpl(stream);
        } catch (IOException e) {
            throw new RepositoryException("failed to create Binary instance", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Value createValue(Binary value) {
        return new BinaryValue(value);
    }

    /**
     * {@inheritDoc}
     */
    public Value createValue(BigDecimal value) {
        return new DecimalValue(value);
    }

    /**
     * {@inheritDoc}
     */
    public Value createValue(Node node, boolean weak)
            throws RepositoryException {
        if (weak) {
            return new WeakReferenceValue(node);
        } else {
            return new ReferenceValue(node);
        }
    }

}
