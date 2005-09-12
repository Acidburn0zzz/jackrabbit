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
package org.apache.jackrabbit.core;

import org.apache.jackrabbit.core.nodetype.EffectiveNodeType;
import org.apache.jackrabbit.core.nodetype.NodeDef;
import org.apache.jackrabbit.core.nodetype.NodeTypeConflictException;
import org.apache.jackrabbit.core.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.core.nodetype.PropDef;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.QName;
import org.apache.log4j.Logger;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.ConstraintViolationException;
import java.util.HashSet;

/**
 * Utility class for validating an item against constraints
 * specified by its definition.
 */
public class ItemValidator {

    /**
     * Logger instance for this class
     */
    private static Logger log = Logger.getLogger(ItemValidator.class);

    /**
     * node type registry
     */
    protected final NodeTypeRegistry ntReg;

    /**
     * hierarchy manager used for generating error msg's
     * that contain human readable paths
     * @see #safeGetJCRPath(ItemId)
     */
    protected final HierarchyManager hierMgr;
    /**
     * namespace resolver used for generating error msg's
     * that contain human readable paths
     * @see #safeGetJCRPath(Path)
     */
    protected final NamespaceResolver nsResolver;

    /**
     * Creates a new <code>ItemValidator</code> instance.
     *
     * @param ntReg      node type registry
     * @param hierMgr    hierarchy manager
     * @param nsResolver namespace resolver
     */
    public ItemValidator(NodeTypeRegistry ntReg,
                         HierarchyManager hierMgr,
                         NamespaceResolver nsResolver) {
        this.ntReg = ntReg;
        this.hierMgr = hierMgr;
        this.nsResolver = nsResolver;
    }

    /**
     * Checks whether the given node state satisfies the constraints specified
     * by its primary and mixin node types. The following validations/checks are
     * performed:
     * <ul>
     * <li>check if its node type satisfies the 'required node types' constraint
     * specified in its definition</li>
     * <li>check if all 'mandatory' child items exist</li>
     * <li>for every property: check if the property value satisfies the
     * value constraints specified in the property's definition</li>
     * </ul>
     *
     * @param nodeState state of node to be validated
     * @throws ConstraintViolationException if any of the validations fail
     * @throws RepositoryException          if another error occurs
     */
    public void validate(NodeState nodeState)
            throws ConstraintViolationException, RepositoryException {
        // effective primary node type
        EffectiveNodeType entPrimary =
                ntReg.getEffectiveNodeType(nodeState.getNodeTypeName());
        // effective node type (primary type incl. mixins)
        EffectiveNodeType entPrimaryAndMixins = getEffectiveNodeType(nodeState);
        NodeDef def = ntReg.getNodeDef(nodeState.getDefinitionId());

        // check if primary type satisfies the 'required node types' constraint
        QName[] requiredPrimaryTypes = def.getRequiredPrimaryTypes();
        for (int i = 0; i < requiredPrimaryTypes.length; i++) {
            if (!entPrimary.includesNodeType(requiredPrimaryTypes[i])) {
                String msg = safeGetJCRPath(nodeState.getId())
                        + ": missing required primary type "
                        + requiredPrimaryTypes[i];
                log.debug(msg);
                throw new ConstraintViolationException(msg);
            }
        }
        // mandatory properties
        PropDef[] pda = entPrimaryAndMixins.getMandatoryPropDefs();
        for (int i = 0; i < pda.length; i++) {
            PropDef pd = pda[i];
            if (!nodeState.hasPropertyName(pd.getName())) {
                String msg = safeGetJCRPath(nodeState.getId())
                        + ": mandatory property " + pd.getName()
                        + " does not exist";
                log.debug(msg);
                throw new ConstraintViolationException(msg);
            }
        }
        // mandatory child nodes
        NodeDef[] cnda = entPrimaryAndMixins.getMandatoryNodeDefs();
        for (int i = 0; i < cnda.length; i++) {
            NodeDef cnd = cnda[i];
            if (!nodeState.hasChildNodeEntry(cnd.getName())) {
                String msg = safeGetJCRPath(nodeState.getId())
                        + ": mandatory child node " + cnd.getName()
                        + " does not exist";
                log.debug(msg);
                throw new ConstraintViolationException(msg);
            }
        }
    }

    /**
     * Checks whether the given property state satisfies the constraints
     * specified by its definition. The following validations/checks are
     * performed:
     * <ul>
     * <li>check if the type of the property values does comply with the
     * requiredType specified in the property's definition</li>
     * <li>check if the property values satisfy the value constraints
     * specified in the property's definition</li>
     * </ul>
     *
     * @param propState state of property to be validated
     * @throws ConstraintViolationException if any of the validations fail
     * @throws RepositoryException          if another error occurs
     */
    public void validate(PropertyState propState)
            throws ConstraintViolationException, RepositoryException {
        PropDef def = ntReg.getPropDef(propState.getDefinitionId());
        InternalValue[] values = propState.getValues();
        int type = PropertyType.UNDEFINED;
        for (int i = 0; i < values.length; i++) {
            if (type == PropertyType.UNDEFINED) {
                type = values[i].getType();
            } else if (type != values[i].getType()) {
                throw new ConstraintViolationException(safeGetJCRPath(propState.getId())
                        + ": inconsistent value types");
            }
            if (def.getRequiredType() != PropertyType.UNDEFINED
                    && def.getRequiredType() != type) {
                throw new ConstraintViolationException(safeGetJCRPath(propState.getId())
                        + ": requiredType constraint is not satisfied");
            }
        }
        EffectiveNodeType.checkSetPropertyValueConstraints(def, values);
    }

    //-------------------------------------------------< misc. helper methods >
    /**
     * Helper method that builds the effective (i.e. merged and resolved)
     * node type representation of the specified node's primary and mixin
     * node types.
     *
     * @param state
     * @return the effective node type
     * @throws javax.jcr.RepositoryException
     */
    public EffectiveNodeType getEffectiveNodeType(NodeState state)
            throws RepositoryException {
        // build effective node type of mixins & primary type:
        // existing mixin's
        HashSet set = new HashSet(state.getMixinTypeNames());
        // primary type
        set.add(state.getNodeTypeName());
        try {
            QName[] ntNames = (QName[]) set.toArray(new QName[set.size()]);
            return ntReg.getEffectiveNodeType(ntNames);
        } catch (NodeTypeConflictException ntce) {
            String msg =
                    "internal error: failed to build effective node type for node "
                    + state.getUUID();
            log.debug(msg);
            throw new RepositoryException(msg, ntce);
        }
    }

    /**
     * Failsafe conversion of internal <code>Path</code> to JCR path for use in
     * error messages etc.
     *
     * @param path path to convert
     * @return JCR path
     */
    public String safeGetJCRPath(Path path) {
        try {
            return path.toJCRPath(nsResolver);
        } catch (NoPrefixDeclaredException npde) {
            log.error("failed to convert " + path.toString() + " to JCR path.");
            // return string representation of internal path as a fallback
            return path.toString();
        }
    }

    /**
     * Failsafe translation of internal <code>ItemId</code> to JCR path for use
     * in error messages etc.
     *
     * @param id id to translate
     * @return JCR path
     */
    public String safeGetJCRPath(ItemId id) {
        try {
            return safeGetJCRPath(hierMgr.getPath(id));
        } catch (RepositoryException re) {
            log.error(id + ": failed to build path");
            // return string representation if id as a fallback
            return id.toString();
        }
    }
}
