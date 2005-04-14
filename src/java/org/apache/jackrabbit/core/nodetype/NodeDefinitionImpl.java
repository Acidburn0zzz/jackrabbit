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

import org.apache.jackrabbit.core.Constants;
import org.apache.jackrabbit.core.NamespaceResolver;
import org.apache.jackrabbit.core.QName;
import org.apache.log4j.Logger;

import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeDefinition;

/**
 * This class implements the NodeDef interface.
 */
public class NodeDefinitionImpl extends ItemDefinitionImpl implements NodeDefinition {

    /**
     * the default logger
     */
    private static Logger log = Logger.getLogger(NodeDefImpl.class);

    /**
     * Package private constructor
     *
     * @param nodeDef    child node definition
     * @param ntMgr      node type manager
     * @param nsResolver namespace resolver
     */
    NodeDefinitionImpl(NodeDef nodeDef, NodeTypeManagerImpl ntMgr,
                NamespaceResolver nsResolver) {
        super(nodeDef, ntMgr, nsResolver);
    }

    /**
     * Returns the underlaying item def
     * @return
     */
    public NodeDef unwrap() {
        return (NodeDef) itemDef;
    }

    //--------------------------------------------------------------< NodeDef >
    /**
     * {@inheritDoc}
     */
    public NodeType getDefaultPrimaryType() {
        QName ntName = ((NodeDef) itemDef).getDefaultPrimaryType();
        if (ntName == null) {
            return null;
        }
        try {
            return ntMgr.getNodeType(ntName);
        } catch (NoSuchNodeTypeException e) {
            // should never get here
            log.error("invalid default node type " + ntName, e);
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    public NodeType[] getRequiredPrimaryTypes() {
        QName[] ntNames = ((NodeDef) itemDef).getRequiredPrimaryTypes();
        try {
            if (ntNames == null || ntNames.length == 0) {
                // return "nt:base"
                return new NodeType[]{ntMgr.getNodeType(Constants.NT_BASE)};
            } else {
                NodeType[] nodeTypes = new NodeType[ntNames.length];
                for (int i = 0; i < ntNames.length; i++) {
                    nodeTypes[i] = ntMgr.getNodeType(ntNames[i]);
                }
                return nodeTypes;
            }
        } catch (NoSuchNodeTypeException e) {
            // should never get here
            log.error("required node type does not exist", e);
            return new NodeType[0];
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean allowsSameNameSiblings() {
        return ((NodeDef) itemDef).allowsSameNameSiblings();
    }
}

