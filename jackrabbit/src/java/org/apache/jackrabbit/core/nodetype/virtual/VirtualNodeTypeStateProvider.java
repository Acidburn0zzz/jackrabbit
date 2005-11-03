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
package org.apache.jackrabbit.core.nodetype.virtual;

import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.nodetype.ItemDef;
import org.apache.jackrabbit.core.nodetype.NodeDef;
import org.apache.jackrabbit.core.nodetype.NodeDefId;
import org.apache.jackrabbit.core.nodetype.NodeTypeDef;
import org.apache.jackrabbit.core.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.core.nodetype.PropDef;
import org.apache.jackrabbit.core.nodetype.ValueConstraint;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.NoSuchItemStateException;
import org.apache.jackrabbit.core.state.NodeReferences;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.core.virtual.AbstractVISProvider;
import org.apache.jackrabbit.core.virtual.VirtualNodeState;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.uuid.UUID;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.version.OnParentVersionAction;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * This Class implements a virtual item state provider that exposes the
 * registered nodetypes.
 */
public class VirtualNodeTypeStateProvider extends AbstractVISProvider {

    /**
     * the parent id
     */
    private final String parentId;

    /**
     * @param ntReg
     * @param rootNodeId
     * @param parentId
     */
    public VirtualNodeTypeStateProvider(NodeTypeRegistry ntReg, String rootNodeId, String parentId) {
        super(ntReg, new NodeId(rootNodeId));
        this.parentId = parentId;
        try {
            getRootState();
        } catch (ItemStateException e) {
            // ignore
        }
    }

    /**
     * {@inheritDoc}
     * <p/>
     * currently we have no dynamic ones, we just recreate the entire nodetypes tree
     */
    protected VirtualNodeState createRootNodeState() throws RepositoryException {
        VirtualNodeState root = new VirtualNodeState(this, parentId, rootNodeId.getUUID(), QName.REP_NODETYPES, null);
        NodeDefId id = ntReg.getEffectiveNodeType(QName.REP_SYSTEM).getApplicableChildNodeDef(QName.JCR_NODETYPES, QName.REP_NODETYPES).getId();
        root.setDefinitionId(id);
        QName[] ntNames = ntReg.getRegisteredNodeTypes();
        for (int i = 0; i < ntNames.length; i++) {
            NodeTypeDef ntDef = ntReg.getNodeTypeDef(ntNames[i]);
            VirtualNodeState ntState = createNodeTypeState(root, ntDef);
            root.addChildNodeEntry(ntNames[i], ntState.getUUID());
            // add as hard reference
            root.addStateReference(ntState);
        }
        return root;
    }

    /**
     * {@inheritDoc}
     */
    protected boolean internalHasNodeState(NodeId id) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    protected VirtualNodeState internalGetNodeState(NodeId id) throws NoSuchItemStateException, ItemStateException {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public void onNodeTypeAdded(QName ntName) throws RepositoryException {
        try {
            VirtualNodeState root = (VirtualNodeState) getRootState();
            NodeTypeDef ntDef = ntReg.getNodeTypeDef(ntName);
            VirtualNodeState ntState = createNodeTypeState(root, ntDef);
            root.addChildNodeEntry(ntName, ntState.getUUID());

            // add as hard reference
            root.addStateReference(ntState);
            root.notifyStateUpdated();
        } catch (ItemStateException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void onNodeTypeModified(QName ntName) throws RepositoryException {
        // todo: do more efficient reloading
        try {
            getRootState().discard();
        } catch (ItemStateException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void onNodeTypeRemoved(QName ntName) throws RepositoryException {
        // todo: do more efficient reloading
        try {
            getRootState().discard();
        } catch (ItemStateException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     * Creates a node type state
     *
     * @param parent
     * @param ntDef
     * @return
     * @throws RepositoryException
     */
    private VirtualNodeState createNodeTypeState(VirtualNodeState parent, NodeTypeDef ntDef) throws RepositoryException {
        String uuid = calculateStableUUID(ntDef.getName().toString());
        VirtualNodeState ntState = createNodeState(parent, ntDef.getName(), uuid, QName.NT_NODETYPE);

        // add properties
        ntState.setPropertyValue(QName.JCR_NODETYPENAME, InternalValue.create(ntDef.getName()));
        ntState.setPropertyValues(QName.JCR_SUPERTYPES, PropertyType.NAME, InternalValue.create(ntDef.getSupertypes()));
        ntState.setPropertyValue(QName.JCR_ISMIXIN, InternalValue.create(ntDef.isMixin()));
        ntState.setPropertyValue(QName.JCR_HASORDERABLECHILDNODES, InternalValue.create(ntDef.hasOrderableChildNodes()));
        if (ntDef.getPrimaryItemName() != null) {
            ntState.setPropertyValue(QName.JCR_PRIMARYITEMNAME, InternalValue.create(ntDef.getPrimaryItemName()));
        }

        // add property defs
        PropDef[] propDefs = ntDef.getPropertyDefs();
        for (int i = 0; i < propDefs.length; i++) {
            VirtualNodeState pdState = createPropertyDefState(ntState, propDefs[i], ntDef, i);
            ntState.addChildNodeEntry(QName.JCR_PROPERTYDEFINITION, pdState.getUUID());
            // add as hard reference
            ntState.addStateReference(pdState);
        }

        // add child node defs
        NodeDef[] cnDefs = ntDef.getChildNodeDefs();
        for (int i = 0; i < cnDefs.length; i++) {
            VirtualNodeState cnState = createChildNodeDefState(ntState, cnDefs[i], ntDef, i);
            ntState.addChildNodeEntry(QName.JCR_CHILDNODEDEFINITION, cnState.getUUID());
            // add as hard reference
            ntState.addStateReference(cnState);
        }

        return ntState;
    }

    /**
     * creates a node state for the given property def
     *
     * @param parent
     * @param propDef
     * @return
     * @throws RepositoryException
     */
    private VirtualNodeState createPropertyDefState(VirtualNodeState parent,
                                                    PropDef propDef,
                                                    NodeTypeDef ntDef, int n)
            throws RepositoryException {
        String uuid = calculateStableUUID(ntDef.getName().toString() + "/" + QName.JCR_PROPERTYDEFINITION.toString() + "/" + n);
        VirtualNodeState pState = createNodeState(parent, QName.JCR_PROPERTYDEFINITION, uuid, QName.NT_PROPERTYDEFINITION);
        // add properties
        if (!propDef.definesResidual()) {
            pState.setPropertyValue(QName.JCR_NAME, InternalValue.create(propDef.getName()));
        }
        pState.setPropertyValue(QName.JCR_AUTOCREATED, InternalValue.create(propDef.isAutoCreated()));
        pState.setPropertyValue(QName.JCR_MANDATORY, InternalValue.create(propDef.isMandatory()));
        pState.setPropertyValue(QName.JCR_ONPARENTVERSION,
                InternalValue.create(OnParentVersionAction.nameFromValue(propDef.getOnParentVersion())));
        pState.setPropertyValue(QName.JCR_PROTECTED, InternalValue.create(propDef.isProtected()));
        pState.setPropertyValue(QName.JCR_MULTIPLE, InternalValue.create(propDef.isMultiple()));
        pState.setPropertyValue(QName.JCR_REQUIREDTYPE, InternalValue.create(PropertyType.nameFromValue(propDef.getRequiredType()).toUpperCase()));
        pState.setPropertyValues(QName.JCR_DEFAULTVALUES, PropertyType.STRING, propDef.getDefaultValues());
        ValueConstraint[] vc = propDef.getValueConstraints();
        InternalValue[] vals = new InternalValue[vc.length];
        for (int i = 0; i < vc.length; i++) {
            vals[i] = InternalValue.create(vc[i].getDefinition());
        }
        pState.setPropertyValues(QName.JCR_VALUECONSTRAINTS, PropertyType.STRING, vals);
        return pState;
    }

    /**
     * creates a node state for the given child node def
     *
     * @param parent
     * @param cnDef
     * @return
     * @throws RepositoryException
     */
    private VirtualNodeState createChildNodeDefState(VirtualNodeState parent,
                                                     NodeDef cnDef,
                                                     NodeTypeDef ntDef, int n)
            throws RepositoryException {
        String uuid = calculateStableUUID(ntDef.getName().toString() + "/" + QName.JCR_CHILDNODEDEFINITION.toString() + "/" + n);
        VirtualNodeState pState = createNodeState(parent, QName.JCR_CHILDNODEDEFINITION, uuid, QName.NT_CHILDNODEDEFINITION);
        // add properties
        if (!cnDef.definesResidual()) {
            pState.setPropertyValue(QName.JCR_NAME, InternalValue.create(cnDef.getName()));
        }
        pState.setPropertyValue(QName.JCR_AUTOCREATED, InternalValue.create(cnDef.isAutoCreated()));
        pState.setPropertyValue(QName.JCR_MANDATORY, InternalValue.create(cnDef.isMandatory()));
        pState.setPropertyValue(QName.JCR_ONPARENTVERSION,
                InternalValue.create(OnParentVersionAction.nameFromValue(cnDef.getOnParentVersion())));
        pState.setPropertyValue(QName.JCR_PROTECTED, InternalValue.create(cnDef.isProtected()));
        pState.setPropertyValues(QName.JCR_REQUIREDPRIMARYTYPES,
                PropertyType.NAME, InternalValue.create(cnDef.getRequiredPrimaryTypes()));
        if (cnDef.getDefaultPrimaryType() != null) {
            pState.setPropertyValue(QName.JCR_DEFAULTPRIMARYTYPE, InternalValue.create(cnDef.getDefaultPrimaryType()));
        }
        pState.setPropertyValue(QName.JCR_SAMENAMESIBLINGS, InternalValue.create(cnDef.allowsSameNameSiblings()));
        return pState;
    }

    /**
     * Calclulates a stable uuid out of the given string. The alogrith does a
     * MD5 digest from the string an converts it into the uuid format.
     *
     * @param name
     * @return
     * @throws RepositoryException
     */
    private static String calculateStableUUID(String name) throws RepositoryException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(name.getBytes("utf-8"));
            return new UUID(digest).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RepositoryException(e);
        } catch (UnsupportedEncodingException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     * @inheritDoc
     */ 
    public boolean setNodeReferences(NodeReferences refs) {
        return false;
    }
}
