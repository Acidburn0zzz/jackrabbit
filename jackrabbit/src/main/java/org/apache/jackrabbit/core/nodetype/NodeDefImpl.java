/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
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

import org.apache.jackrabbit.name.QName;

import java.util.Arrays;

/**
 * This class implements the <code>NodeDef</code> interface and additionally
 * provides setter methods for the various node definition attributes.
 */
public class NodeDefImpl extends ItemDefImpl implements NodeDef {

    /**
     * The name of the default primary type.
     */
    private QName defaultPrimaryType = null;

    /**
     * The names of the required primary types.
     */
    private QName[] requiredPrimaryTypes = new QName[] { QName.NT_BASE };

    /**
     * The 'allowsSameNameSiblings' flag.
     */
    private boolean allowsSameNameSiblings = false;

    /**
     * The identifier of this node definition. The identifier is lazily computed
     * based on the characteristics of this node definition and reset on every
     * attribute change.
     */
    private NodeDefId id = null;

    /**
     * Default constructor.
     */
    public NodeDefImpl() {
    }

    /**
     * Sets the name of default primary type.
     *
     * @param defaultNodeType
     */
    public void setDefaultPrimaryType(QName defaultNodeType) {
        // reset id field in order to force lazy recomputation of identifier
        id = null;
        this.defaultPrimaryType = defaultNodeType;
    }

    /**
     * Sets the names of the required primary types.
     *
     * @param requiredPrimaryTypes
     */
    public void setRequiredPrimaryTypes(QName[] requiredPrimaryTypes) {
        // reset id field in order to force lazy recomputation of identifier
        id = null;
        if (requiredPrimaryTypes == null) {
            throw new IllegalArgumentException("requiredPrimaryTypes can not be null");
        }
        this.requiredPrimaryTypes = requiredPrimaryTypes;
    }

    /**
     * Sets the 'allowsSameNameSiblings' flag.
     *
     * @param allowsSameNameSiblings
     */
    public void setAllowsSameNameSiblings(boolean allowsSameNameSiblings) {
        // reset id field in order to force lazy recomputation of identifier
        id = null;
        this.allowsSameNameSiblings = allowsSameNameSiblings;
    }

    //------------------------------------------------< ItemDefImpl overrides >
    /**
     * {@inheritDoc}
     */
    public void setDeclaringNodeType(QName declaringNodeType) {
        // reset id field in order to force lazy recomputation of identifier
        id = null;
        super.setDeclaringNodeType(declaringNodeType);
    }

    /**
     * {@inheritDoc}
     */
    public void setName(QName name) {
        // reset id field in order to force lazy recomputation of identifier
        id = null;
        super.setName(name);
    }

    /**
     * {@inheritDoc}
     */
    public void setAutoCreated(boolean autoCreated) {
        // reset id field in order to force lazy recomputation of identifier
        id = null;
        super.setAutoCreated(autoCreated);
    }

    /**
     * {@inheritDoc}
     */
    public void setOnParentVersion(int onParentVersion) {
        // reset id field in order to force lazy recomputation of identifier
        id = null;
        super.setOnParentVersion(onParentVersion);
    }

    /**
     * {@inheritDoc}
     */
    public void setProtected(boolean writeProtected) {
        // reset id field in order to force lazy recomputation of identifier
        id = null;
        super.setProtected(writeProtected);
    }

    /**
     * {@inheritDoc}
     */
    public void setMandatory(boolean mandatory) {
        // reset id field in order to force lazy recomputation of identifier
        id = null;
        super.setMandatory(mandatory);
    }

    //--------------------------------------------------------------< NodeDef >
    /**
     * {@inheritDoc}
     * <p/>
     * The identifier is computed based on the characteristics of this property
     * definition, i.e. modifying attributes of this property definition will
     * have impact on the identifier returned by this method.
     */
    public NodeDefId getId() {
        if (id == null) {
            // generate new identifier based on this node definition
            id = new NodeDefId(this);
        }
        return id;
    }

    /**
     * {@inheritDoc}
     */
    public QName getDefaultPrimaryType() {
        return defaultPrimaryType;
    }

    /**
     * {@inheritDoc}
     */
    public QName[] getRequiredPrimaryTypes() {
        return requiredPrimaryTypes;
    }

    /**
     * {@inheritDoc}
     */
    public boolean allowsSameNameSiblings() {
        return allowsSameNameSiblings;
    }

    /**
     * {@inheritDoc}
     *
     * @return always <code>true</code>
     */
    public boolean definesNode() {
        return true;
    }

    //-------------------------------------------< java.lang.Object overrides >
    /**
     * Compares two node definitions for equality. Returns <code>true</code>
     * if the given object is a node defintion and has the same attributes
     * as this node definition.
     *
     * @param obj the object to compare this node definition with
     * @return <code>true</code> if the object is equal to this node definition,
     *         <code>false</code> otherwise
     * @see Object#equals(Object)
     */
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof NodeDefImpl) {
            NodeDefImpl other = (NodeDefImpl) obj;
            return super.equals(obj)
                    && Arrays.equals(requiredPrimaryTypes, other.requiredPrimaryTypes)
                    && (defaultPrimaryType == null
                            ? other.defaultPrimaryType == null
                            : defaultPrimaryType.equals(other.defaultPrimaryType))
                    && allowsSameNameSiblings == other.allowsSameNameSiblings;
        }
        return false;
    }

    /**
     * Returns zero to satisfy the Object equals/hashCode contract.
     * This class is mutable and not meant to be used as a hash key.
     *
     * @return always zero
     * @see Object#hashCode()
     */
    public int hashCode() {
        return 0;
    }

}
