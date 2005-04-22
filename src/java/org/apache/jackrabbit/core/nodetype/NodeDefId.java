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
package org.apache.jackrabbit.core.nodetype;

import org.apache.jackrabbit.core.QName;

import java.io.Serializable;
import java.util.TreeSet;

/**
 * <code>NodeDefId</code> uniquely identifies a <code>NodeDef</code> in the
 * node type registry.
 */
public class NodeDefId implements Serializable {

    /**
     * Serialization UID of this class.
     */
    static final long serialVersionUID = 7020286139887664713L;

    /**
     * The internal id is computed based on the characteristics of the
     * <code>NodeDef</code> that this <code>NodeDefId</code> identifies.
     */
    private final int id;

    /**
     * Creates a new <code>NodeDefId</code> that serves as identifier for
     * the given <code>NodeDef</code>. An internal id is computed based on
     * the characteristics of the <code>NodeDef</code> that it identifies.
     *
     * @param def <code>NodeDef</code> to create identifier for
     */
    NodeDefId(NodeDef def) {
        if (def == null) {
            throw new IllegalArgumentException("NodeDef argument can not be null");
        }
        // build key (format: <declaringNodeType>/<name>/<requiredPrimaryTypes>)
        StringBuffer sb = new StringBuffer();

        sb.append(def.getDeclaringNodeType().toString());
        sb.append('/');
        if (def.definesResidual()) {
            sb.append('*');
        } else {
            sb.append(def.getName().toString());
        }
        sb.append('/');
        // set of required node type names, sorted in ascending order
        TreeSet set = new TreeSet();
        QName[] names = def.getRequiredPrimaryTypes();
        for (int i = 0; i < names.length; i++) {
            set.add(names[i]);
        }
        sb.append(set.toString());

        id = sb.toString().hashCode();
    }

    /**
     * Private constructor that creates a <code>NodeDefId</code> using an
     * internal id
     *
     * @param id internal id
     */
    private NodeDefId(int id) {
        this.id = id;
    }

    /**
     * Returns a <code>NodeDefId</code> holding the value of the specified
     * string. The string must be in the format returned by the
     * <code>NodeDefId.toString()</code> method.
     *
     * @param s a <code>String</code> containing the <code>NodeDefId</code>
     *          representation to be parsed.
     * @return the <code>NodeDefId</code> represented by the argument
     * @throws IllegalArgumentException if the specified string can not be parsed
     *                                  as a <code>NodeDefId</code>.
     * @see #toString()
     */
    public static NodeDefId valueOf(String s) {
        if (s == null) {
            throw new IllegalArgumentException("invalid NodeDefId literal");
        }
        return new NodeDefId(Integer.parseInt(s));
    }

    //-------------------------------------------< java.lang.Object overrides >
    /**
     * {@inheritDoc}
     */
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof NodeDefId) {
            NodeDefId other = (NodeDefId) obj;
            return id == other.id;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public String toString() {
        return Integer.toString(id);
    }

    /**
     * {@inheritDoc}
     */
    public int hashCode() {
        // the computed 'id' is used as hash code
        return id;
    }
}
