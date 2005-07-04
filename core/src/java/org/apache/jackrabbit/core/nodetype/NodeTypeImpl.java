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

import org.apache.jackrabbit.BaseException;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.name.IllegalNameException;
import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.UnknownPrefixException;
import org.apache.log4j.Logger;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.PropertyDefinition;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * A <code>NodeTypeImpl</code> ...
 */
public class NodeTypeImpl implements NodeType {

    private static Logger log = Logger.getLogger(NodeTypeImpl.class);

    private final NodeTypeDef ntd;
    private final EffectiveNodeType ent;
    private final NodeTypeManagerImpl ntMgr;
    // namespace resolver used to translate qualified names to JCR names
    private final NamespaceResolver nsResolver;

    /**
     * Package private constructor
     * <p/>
     * Creates a valid node type instance.
     * We assume that the node type definition is valid and all referenced
     * node types (supertypes, required node types etc.) do exist and are valid.
     *
     * @param ent        the effective (i.e. merged and resolved) node type representation
     * @param ntd        the definition of this node type
     * @param ntMgr      the node type manager associated with this node type
     * @param nsResolver namespace resolver
     */
    NodeTypeImpl(EffectiveNodeType ent, NodeTypeDef ntd,
                 NodeTypeManagerImpl ntMgr, NamespaceResolver nsResolver) {
        this.ent = ent;
        this.ntMgr = ntMgr;
        this.nsResolver = nsResolver;
        try {
            // store a clone of the definition
            this.ntd = (NodeTypeDef) ntd.clone();
        } catch (CloneNotSupportedException e) {
            // should never get here
            log.fatal("internal error", e);
            throw new InternalError(e.getMessage());
        }
    }

    /**
     * Returns the applicable child node definition for a child node with the
     * specified name.
     *
     * @param nodeName
     * @return
     * @throws RepositoryException if no applicable child node definition
     *                             could be found
     */
    public NodeDefinitionImpl getApplicableChildNodeDefinition(QName nodeName)
            throws RepositoryException {
        return getApplicableChildNodeDefinition(nodeName, null);
    }

    /**
     * Returns the applicable child node definition for a child node with the
     * specified name and node type.
     *
     * @param nodeName
     * @param nodeTypeName
     * @return
     * @throws RepositoryException if no applicable child node definition
     *                             could be found
     */
    public NodeDefinitionImpl getApplicableChildNodeDefinition(QName nodeName,
                                                               QName nodeTypeName)
            throws RepositoryException {
        return ntMgr.getNodeDefinition(
                ent.getApplicableChildNodeDef(nodeName, nodeTypeName).getId());
    }

    /**
     * Returns the applicable property definition for a property with the
     * specified name and type.
     *
     * @param propertyName
     * @param type
     * @param multiValued
     * @return
     * @throws RepositoryException if no applicable property definition
     *                             could be found
     */
    public PropertyDefinitionImpl getApplicablePropertyDefinition(QName propertyName,
                                                                  int type,
                                                                  boolean multiValued)
            throws RepositoryException {
        return ntMgr.getPropertyDefinition(
                ent.getApplicablePropertyDef(propertyName, type, multiValued).getId());
    }

    /**
     * Checks if this node type is directly or indirectly derived from the
     * specified node type.
     *
     * @param nodeTypeName
     * @return true if this node type is directly or indirectly derived from the
     *         specified node type, otherwise false.
     */
    public boolean isDerivedFrom(QName nodeTypeName) {
        return !nodeTypeName.equals(ntd.getName()) && ent.includesNodeType(nodeTypeName);
    }

    /**
     * Returns the definition of this node type.
     *
     * @return the definition of this node type
     */
    public NodeTypeDef getDefinition() {
        try {
            // return a clone of the definition
            return (NodeTypeDef) ntd.clone();
        } catch (CloneNotSupportedException e) {
            // should never get here
            log.fatal("internal error", e);
            throw new InternalError(e.getMessage());
        }
    }

    /**
     * Returns an array containing only those child node definitions of this
     * node type (including the child node definitions inherited from supertypes
     * of this node type) where <code>{@link NodeDefinition#isAutoCreated()}</code>
     * returns <code>true</code>.
     *
     * @return an array of child node definitions.
     * @see NodeDefinition#isAutoCreated
     */
    public NodeDefinition[] getAutoCreatedNodeDefinitions() {
        NodeDef[] cnda = ent.getAutoCreateNodeDefs();
        NodeDefinition[] nodeDefs = new NodeDefinition[cnda.length];
        for (int i = 0; i < cnda.length; i++) {
            nodeDefs[i] = ntMgr.getNodeDefinition(cnda[i].getId());
        }
        return nodeDefs;
    }

    /**
     * Returns an array containing only those property definitions of this
     * node type (including the property definitions inherited from supertypes
     * of this node type) where <code>{@link PropertyDefinition#isAutoCreated()}</code>
     * returns <code>true</code>.
     *
     * @return an array of property definitions.
     * @see PropertyDefinition#isAutoCreated
     */
    public PropertyDefinition[] getAutoCreatedPropertyDefinitions() {
        PropDef[] pda = ent.getAutoCreatePropDefs();
        PropertyDefinition[] propDefs = new PropertyDefinition[pda.length];
        for (int i = 0; i < pda.length; i++) {
            propDefs[i] = ntMgr.getPropertyDefinition(pda[i].getId());
        }
        return propDefs;
    }

    /**
     * Returns an array containing only those property definitions of this
     * node type (including the property definitions inherited from supertypes
     * of this node type) where <code>{@link PropertyDefinition#isMandatory()}</code>
     * returns <code>true</code>.
     *
     * @return an array of property definitions.
     * @see PropertyDefinition#isMandatory
     */
    public PropertyDefinition[] getMandatoryPropertyDefinitions() {
        PropDef[] pda = ent.getMandatoryPropDefs();
        PropertyDefinition[] propDefs = new PropertyDefinition[pda.length];
        for (int i = 0; i < pda.length; i++) {
            propDefs[i] = ntMgr.getPropertyDefinition(pda[i].getId());
        }
        return propDefs;
    }

    /**
     * Returns an array containing only those child node definitions of this
     * node type (including the child node definitions inherited from supertypes
     * of this node type) where <code>{@link NodeDefinition#isMandatory()}</code>
     * returns <code>true</code>.
     *
     * @return an array of child node definitions.
     * @see NodeDefinition#isMandatory
     */
    public NodeDefinition[] getMandatoryNodeDefinitions() {
        NodeDef[] cnda = ent.getMandatoryNodeDefs();
        NodeDefinition[] nodeDefs = new NodeDefinition[cnda.length];
        for (int i = 0; i < cnda.length; i++) {
            nodeDefs[i] = ntMgr.getNodeDefinition(cnda[i].getId());
        }
        return nodeDefs;
    }

    /**
     * Tests if the value constraints defined in the property definition
     * <code>def</code> are satisfied by the the specified <code>values</code>.
     * <p/>
     * Note that the <i>protected</i> flag is not checked. Also note that no
     * type conversions are attempted if the type of the given values does not
     * match the required type as specified in the given definition.
     *
     * @param def    The definiton of the property
     * @param values An array of <code>InternalValue</code> objects.
     * @throws ConstraintViolationException
     * @throws RepositoryException
     */
    public static void checkSetPropertyValueConstraints(PropertyDefinitionImpl def,
                                                        InternalValue[] values)
            throws ConstraintViolationException, RepositoryException {
        EffectiveNodeType.checkSetPropertyValueConstraints(def.unwrap(), values);
    }

    /**
     * Returns the 'internal', i.e. the fully qualified name.
     *
     * @return the qualified name
     */
    public QName getQName() {
        return ntd.getName();
    }

    /**
     * Returns all <i>inherited</i> supertypes of this node type.
     *
     * @return an array of <code>NodeType</code> objects.
     * @see #getSupertypes
     * @see #getDeclaredSupertypes
     */
    public NodeType[] getInheritedSupertypes() {
        // declared supertypes
        QName[] ntNames = ntd.getSupertypes();
        HashSet declared = new HashSet();
        for (int i = 0; i < ntNames.length; i++) {
            declared.add(ntNames[i]);
        }
        // all supertypes
        ntNames = ent.getInheritedNodeTypes();

        // filter from all supertypes those that are not declared
        ArrayList inherited = new ArrayList();
        for (int i = 0; i < ntNames.length; i++) {
            if (!declared.contains(ntNames[i])) {
                try {
                    inherited.add(ntMgr.getNodeType(ntNames[i]));
                } catch (NoSuchNodeTypeException e) {
                    // should never get here
                    log.error("undefined supertype", e);
                    return new NodeType[0];
                }
            }
        }

        return (NodeType[]) inherited.toArray(new NodeType[inherited.size()]);
    }

    //-------------------------------------------------------------< NodeType >
    /**
     * {@inheritDoc}
     */
    public String getName() {
        try {
            return ntd.getName().toJCRName(nsResolver);
        } catch (NoPrefixDeclaredException npde) {
            // should never get here
            log.error("encountered unregistered namespace in node type name", npde);
            return ntd.getName().toString();
        }
    }

    /**
     * {@inheritDoc}
     */
    public String getPrimaryItemName() {
        try {
            QName piName = ntd.getPrimaryItemName();
            if (piName != null) {
                return piName.toJCRName(nsResolver);
            } else {
                return null;
            }
        } catch (NoPrefixDeclaredException npde) {
            // should never get here
            log.error("encountered unregistered namespace in name of primary item", npde);
            return ntd.getName().toString();
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean isMixin() {
        return ntd.isMixin();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isNodeType(String nodeTypeName) {
        QName ntName;
        try {
            ntName = QName.fromJCRName(nodeTypeName, nsResolver);
        } catch (IllegalNameException ine) {
            log.warn("invalid node type name: " + nodeTypeName, ine);
            return false;
        } catch (UnknownPrefixException upe) {
            log.warn("invalid node type name: " + nodeTypeName, upe);
            return false;
        }
        return (getQName().equals(ntName) || isDerivedFrom(ntName));
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasOrderableChildNodes() {
        return ntd.hasOrderableChildNodes();
    }

    /**
     * {@inheritDoc}
     */
    public NodeType[] getSupertypes() {
        QName[] ntNames = ent.getInheritedNodeTypes();
        NodeType[] supertypes = new NodeType[ntNames.length];
        for (int i = 0; i < ntNames.length; i++) {
            try {
                supertypes[i] = ntMgr.getNodeType(ntNames[i]);
            } catch (NoSuchNodeTypeException e) {
                // should never get here
                log.error("undefined supertype", e);
                return new NodeType[0];
            }
        }
        return supertypes;
    }

    /**
     * {@inheritDoc}
     */
    public NodeDefinition[] getChildNodeDefinitions() {
        NodeDef[] cnda = ent.getAllNodeDefs();
        NodeDefinition[] nodeDefs = new NodeDefinition[cnda.length];
        for (int i = 0; i < cnda.length; i++) {
            nodeDefs[i] = ntMgr.getNodeDefinition(cnda[i].getId());
        }
        return nodeDefs;
    }

    /**
     * {@inheritDoc}
     */
    public PropertyDefinition[] getPropertyDefinitions() {
        PropDef[] pda = ent.getAllPropDefs();
        PropertyDefinition[] propDefs = new PropertyDefinition[pda.length];
        for (int i = 0; i < pda.length; i++) {
            propDefs[i] = ntMgr.getPropertyDefinition(pda[i].getId());
        }
        return propDefs;
    }

    /**
     * {@inheritDoc}
     */
    public NodeType[] getDeclaredSupertypes() {
        QName[] ntNames = ntd.getSupertypes();
        NodeType[] supertypes = new NodeType[ntNames.length];
        for (int i = 0; i < ntNames.length; i++) {
            try {
                supertypes[i] = ntMgr.getNodeType(ntNames[i]);
            } catch (NoSuchNodeTypeException e) {
                // should never get here
                log.error("undefined supertype", e);
                return new NodeType[0];
            }
        }
        return supertypes;
    }

    /**
     * {@inheritDoc}
     */
    public NodeDefinition[] getDeclaredChildNodeDefinitions() {
        NodeDef[] cnda = ntd.getChildNodeDefs();
        NodeDefinition[] nodeDefs = new NodeDefinition[cnda.length];
        for (int i = 0; i < cnda.length; i++) {
            nodeDefs[i] = ntMgr.getNodeDefinition(cnda[i].getId());
        }
        return nodeDefs;
    }

    /**
     * {@inheritDoc}
     */
    public boolean canSetProperty(String propertyName, Value value) {
        if (value == null) {
            // setting a property to null is equivalent of removing it
            return canRemoveItem(propertyName);
        }
        try {
            QName name = QName.fromJCRName(propertyName, nsResolver);
            PropertyDefinitionImpl def;
            try {
                // try to get definition that matches the given value type
                def = getApplicablePropertyDefinition(name, value.getType(), false);
            } catch (ConstraintViolationException cve) {
                // fallback: ignore type
                def = getApplicablePropertyDefinition(name, PropertyType.UNDEFINED, false);
            }
            if (def.isProtected()) {
                return false;
            }
            if (def.isMultiple()) {
                return false;
            }
            int targetType;
            if (def.getRequiredType() != PropertyType.UNDEFINED
                    && def.getRequiredType() != value.getType()) {
                // type conversion required
                targetType = def.getRequiredType();
            } else {
                // no type conversion required
                targetType = value.getType();
            }
            // create InternalValue from Value and perform
            // type conversion as necessary
            InternalValue internalValue = InternalValue.create(value, targetType,
                    nsResolver);
            checkSetPropertyValueConstraints(def, new InternalValue[]{internalValue});
            return true;
        } catch (BaseException be) {
            // implementation specific exception, fall through
        } catch (RepositoryException re) {
            // fall through
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean canSetProperty(String propertyName, Value[] values) {
        if (values == null) {
            // setting a property to null is equivalent of removing it
            return canRemoveItem(propertyName);
        }
        try {
            QName name = QName.fromJCRName(propertyName, nsResolver);
            // determine type of values
            int type = PropertyType.UNDEFINED;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    // skip null values as those would be purged
                    continue;
                }
                if (type == PropertyType.UNDEFINED) {
                    type = values[i].getType();
                } else if (type != values[i].getType()) {
                    // inhomogeneous types
                    return false;
                }
            }
            PropertyDefinitionImpl def;
            try {
                // try to get definition that matches the given value type
                def = getApplicablePropertyDefinition(name, type, true);
            } catch (ConstraintViolationException cve) {
                // fallback: ignore type
                def = getApplicablePropertyDefinition(name, PropertyType.UNDEFINED, true);
            }

            if (def.isProtected()) {
                return false;
            }
            if (!def.isMultiple()) {
                return false;
            }
            // determine target type
            int targetType;
            if (def.getRequiredType() != PropertyType.UNDEFINED
                    && def.getRequiredType() != type) {
                // type conversion required
                targetType = def.getRequiredType();
            } else {
                // no type conversion required
                targetType = type;
            }

            ArrayList list = new ArrayList();
            // convert values and compact array (purge null entries)
            for (int i = 0; i < values.length; i++) {
                if (values[i] != null) {
                    // create InternalValue from Value and perform
                    // type conversion as necessary
                    InternalValue internalValue =
                            InternalValue.create(values[i], targetType,
                                    nsResolver);
                    list.add(internalValue);
                }
            }
            InternalValue[] internalValues =
                    (InternalValue[]) list.toArray(new InternalValue[list.size()]);
            checkSetPropertyValueConstraints(def, internalValues);
            return true;
        } catch (BaseException be) {
            // implementation specific exception, fall through
        } catch (RepositoryException re) {
            // fall through
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean canAddChildNode(String childNodeName) {
        try {
            ent.checkAddNodeConstraints(QName.fromJCRName(childNodeName, nsResolver));
            return true;
        } catch (BaseException be) {
            // implementation specific exception, fall through
        } catch (RepositoryException re) {
            // fall through
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean canAddChildNode(String childNodeName, String nodeTypeName) {
        try {
            ent.checkAddNodeConstraints(QName.fromJCRName(childNodeName, nsResolver), QName.fromJCRName(nodeTypeName, nsResolver));
            return true;
        } catch (BaseException be) {
            // implementation specific exception, fall through
        } catch (RepositoryException re) {
            // fall through
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean canRemoveItem(String itemName) {
        try {
            ent.checkRemoveItemConstraints(QName.fromJCRName(itemName, nsResolver));
            return true;
        } catch (BaseException be) {
            // implementation specific exception, fall through
        } catch (RepositoryException re) {
            // fall through
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public PropertyDefinition[] getDeclaredPropertyDefinitions() {
        PropDef[] pda = ntd.getPropertyDefs();
        PropertyDefinition[] propDefs = new PropertyDefinition[pda.length];
        for (int i = 0; i < pda.length; i++) {
            propDefs[i] = ntMgr.getPropertyDefinition(pda[i].getId());
        }
        return propDefs;
    }
}
