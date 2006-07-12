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
package org.apache.jackrabbit.spi2dav;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Value;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.ValueFormatException;
import javax.jcr.PropertyType;
import javax.jcr.ValueFactory;
import java.util.Calendar;
import java.io.InputStream;

/**
 * <code>ValueFactoryImpl</code>...
 */
public class ValueFactoryImpl implements ValueFactory {

    private static Logger log = LoggerFactory.getLogger(ValueFactoryImpl.class);

    private static final ValueFactory factory = new ValueFactoryImpl();

    /**
     * Delegatee for all calls except for REFERENCE values.
     */
    private final ValueFactory commonsFactory = org.apache.jackrabbit.value.ValueFactoryImpl.getInstance();

    /**
     * Constructs a <code>ValueFactory</code> object.
     */
    private ValueFactoryImpl() {
    }

    public static ValueFactory getInstance() {
        return factory;
    }

    public Value createValue(Node value) throws RepositoryException {
        return new ReferenceValue(value);
    }

    public Value createValue(String string) {
        return commonsFactory.createValue(string);
    }

    public Value createValue(String value, int type) throws ValueFormatException {
        Value val;
        switch (type) {
            case PropertyType.REFERENCE:
                val = ReferenceValue.valueOf(value);
                break;
            default:
                val = commonsFactory.createValue(value, type);
        }
        return val;
    }

    public Value createValue(long l) {
        return commonsFactory.createValue(l);
    }

    public Value createValue(double v) {
        return commonsFactory.createValue(v);
    }

    public Value createValue(boolean b) {
        return commonsFactory.createValue(b);
    }

    public Value createValue(Calendar calendar) {
        return commonsFactory.createValue(calendar);
    }

    public Value createValue(InputStream inputStream) {
        return commonsFactory.createValue(inputStream);
    }

    /**
     * A <code>ReferenceValue</code> provides an implementation
     * of the <code>Value</code> interface representing a <code>REFERENCE</code> value
     * (a UUID of an existing node).
     */
    private static class ReferenceValue extends org.apache.jackrabbit.value.ReferenceValue {

        private ReferenceValue(String uuid) {
            super(uuid);
        }

        /**
         * Constructs a <code>ReferenceValue</code> object representing the UUID of
         * an existing node.
         *
         * @param target the node to be referenced
         * @throws IllegalArgumentException If <code>target</code> is nonreferenceable.
         * @throws javax.jcr.RepositoryException      If another error occurs.
         */
        private ReferenceValue(Node target) throws RepositoryException {
            super(target);
        }

        /**
         * Returns a new <code>ReferenceValue</code> initialized to the value
         * represented by the specified <code>String</code>.
         * <p/>
         * The specified <code>String</code> must denote the UUID of an existing
         * node.
         *
         * @param s the string to be parsed.
         * @return a newly constructed <code>ReferenceValue</code> representing the
         *         the specified value.
         * @throws javax.jcr.ValueFormatException If the <code>String</code> is null or empty String.
         */
        public static org.apache.jackrabbit.value.ReferenceValue valueOf(String s) throws ValueFormatException {
            if (s != null && !"".equals(s)) {
                return new ReferenceValue(s);
            } else {
                throw new ValueFormatException("not a valid UUID format");
            }
        }
    }
}