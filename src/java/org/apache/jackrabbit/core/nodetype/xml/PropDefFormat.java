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
package org.apache.jackrabbit.core.nodetype.xml;

import java.util.Vector;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;

import org.apache.jackrabbit.core.InternalValue;
import org.apache.jackrabbit.core.NamespaceResolver;
import org.apache.jackrabbit.core.QName;
import org.apache.jackrabbit.core.nodetype.InvalidConstraintException;
import org.apache.jackrabbit.core.nodetype.InvalidNodeTypeDefException;
import org.apache.jackrabbit.core.nodetype.PropDef;
import org.apache.jackrabbit.core.nodetype.ValueConstraint;
import org.w3c.dom.Element;

/**
 * Utility class for reading and writing property definition XML elements.
 */
class PropDefFormat extends ItemDefFormat {

    /** Name of the required type attribute. */
    private static final String REQUIREDTYPE_ATTRIBUTE = "requiredType";

    /** Name of the value constraints element. */
    private static final String VALUECONSTRAINTS_ELEMENT = "valueConstraints";

    /** Name of the value constraint element. */
    private static final String VALUECONSTRAINT_ELEMENT = "valueConstraint";

    /** Name of the default values element. */
    private static final String DEFAULTVALUES_ELEMENT = "defaultValues";

    /** Name of the default value element. */
    private static final String DEFAULTVALUE_ELEMENT = "defaultValue";

    /** Name of the <code>multiple</code> attribute. */
    private static final String MULTIPLE_ATTRIBUTE = "multiple";

    /** The property definition. */
    private final PropDef def;

    /**
     * Creates a property definition format object. This constructor
     * is used internally by the public reader and writer constructors.
     *
     * @param resolver namespace resolver
     * @param element property definition element
     * @param def property definition
     */
    protected PropDefFormat(
            NamespaceResolver resolver, Element element, PropDef def) {
        super(resolver, element, def);
        this.def = def;
    }

    /**
     * Reads the property definition from the XML element.
     *
     * @param type name of the declaring node type
     * @throws InvalidNodeTypeDefException if the format of the property
     *                                     definition element is invalid
     */
    protected void read(QName type) throws InvalidNodeTypeDefException {
        def.setDeclaringNodeType(type);
        super.read();
        readRequiredType();
        readValueConstraints();
        readDefaultValues();
        readMultiple();
    }

    /**
     * Writes the property definition to the XML element.
     */
    protected void write() throws RepositoryException {
        super.write();
        writeRequiredType();
        writeValueConstraints();
        writeDefaultValues();
        writeMultiple();
    }

    /**
     * Reads and sets the required type of the property definition.
     *
     * @throws InvalidNodeTypeDefException if the format of the property
     *                                     definition element is invalid
     */
    private void readRequiredType() throws InvalidNodeTypeDefException {
        String value = getAttribute(REQUIREDTYPE_ATTRIBUTE);
        def.setRequiredType(PropertyType.valueFromName(value));
    }

    /**
     * Writes the required type of the property definition.
     */
    private void writeRequiredType() {
        String value = PropertyType.nameFromValue(def.getRequiredType());
        setAttribute(REQUIREDTYPE_ATTRIBUTE, value);
    }

    /**
     * Reads and sets the value constraints of the property definition.
     *
     * @throws InvalidNodeTypeDefException if the format of the property
     *                                      definition element is invalid
     */
    private void readValueConstraints() throws InvalidNodeTypeDefException {
        String[] constraints = getGrandChildContents(
                VALUECONSTRAINTS_ELEMENT, VALUECONSTRAINT_ELEMENT);
        if (constraints != null) {
            Vector vector = new Vector();

            int type = def.getRequiredType();
            for (int i = 0; i < constraints.length; i++) {
                try {
                    vector.add(ValueConstraint.create(
                            type, constraints[i], getNamespaceResolver()));
                } catch (InvalidConstraintException e) {
                    throw new InvalidNodeTypeDefException(
                            "Invalid value constraint " + constraints[i], e);
                }
            }

            def.setValueConstraints((ValueConstraint[])
                    vector.toArray(new ValueConstraint[vector.size()]));
        }
    }

    /**
     * Writes the value constraints of the property definition.
     */
    private void writeValueConstraints() {
        ValueConstraint[] constraints = def.getValueConstraints();
        if (constraints != null && constraints.length > 0) {
            Vector values = new Vector();
            for (int i = 0; i < constraints.length; i++) {
                values.add(constraints[i].getDefinition(getNamespaceResolver()));
            }
            setGrandChildContents(
                    VALUECONSTRAINTS_ELEMENT, VALUECONSTRAINT_ELEMENT, values);
        }
    }

    /**
     * Reads and sets the default values of the property definition.
     */
    private void readDefaultValues() throws InvalidNodeTypeDefException {
        String[] defaults = getGrandChildContents(
                DEFAULTVALUES_ELEMENT, DEFAULTVALUE_ELEMENT);
        if (defaults != null) {
            Vector vector = new Vector();

            int type = def.getRequiredType();
            if (type == PropertyType.UNDEFINED) {
                type = PropertyType.STRING;
            }
            for (int i = 0; i < defaults.length; i++) {
                try {
                    vector.add(InternalValue.create(defaults[i], type, getNamespaceResolver()));
                } catch (RepositoryException e) {
                    throw new InvalidNodeTypeDefException(e);
                }
            }

            def.setDefaultValues((InternalValue[])
                    vector.toArray(new InternalValue[vector.size()]));
        }
    }

    /**
     * Writes the default values of the property definition.
     */
    private void writeDefaultValues() throws RepositoryException {
        InternalValue[] defaults = def.getDefaultValues();
        if (defaults != null && defaults.length > 0) {
            Vector values = new Vector();
            for (int i = 0; i < defaults.length; i++) {
                values.add(defaults[i].toJCRValue(getNamespaceResolver()).getString());
            }
            setGrandChildContents(
                    DEFAULTVALUES_ELEMENT, DEFAULTVALUE_ELEMENT, values);
        }
    }

    /**
     * Reads and sets the <code>multiple</code> attribute of the
     * property definition.
     *
     * @throws InvalidNodeTypeDefException if the format of the property
     *                                     definition element is invalid
     */
    private void readMultiple() throws InvalidNodeTypeDefException {
        String value = getAttribute(MULTIPLE_ATTRIBUTE);
        def.setMultiple(Boolean.valueOf(value).booleanValue());
    }

    /**
     * Writes the <code>multiple</code> attribute of the property definition.
     */
    private void writeMultiple() {
        String value = Boolean.toString(def.isMultiple());
        setAttribute(MULTIPLE_ATTRIBUTE, value);
    }

}
