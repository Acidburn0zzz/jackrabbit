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
package org.apache.jackrabbit.jcr2spi.operation;

import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.jcr2spi.state.NodeState;
import org.apache.jackrabbit.jcr2spi.state.ItemStateException;

import javax.jcr.RepositoryException;
import javax.jcr.AccessDeniedException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.version.VersionException;
import javax.jcr.nodetype.NoSuchNodeTypeException;

/**
 * <code>SetMixin</code>...
 */
public class SetMixin extends AbstractOperation {

    private final NodeState nodeState;
    private final QName[] mixinNames;

    private SetMixin(NodeState nodeState, QName[] mixinNames) {
        this.nodeState = nodeState;
        this.mixinNames = mixinNames;

        // set affected states
        addAffectedItemState(nodeState);
        // TODO: correct?
        if (nodeState.hasPropertyName(QName.JCR_MIXINTYPES)) {
            try {
                addAffectedItemState(nodeState.getPropertyState(QName.JCR_MIXINTYPES));
            } catch (ItemStateException e) {
                // should never occur
            }
        }
    }

    //----------------------------------------------------------< Operation >---
    /**
     *
     * @param visitor
     */
    public void accept(OperationVisitor visitor) throws AccessDeniedException, NoSuchNodeTypeException, UnsupportedRepositoryOperationException, VersionException, RepositoryException {
        visitor.visit(this);
    }

    //----------------------------------------< Access Operation Parameters >---
    public NodeState getNodeState() {
        return nodeState;
    }

    public QName[] getMixinNames() {
        return mixinNames;
    }

    //------------------------------------------------------------< Factory >---

    public static Operation create(NodeState nodeState, QName[] mixinNames) {
        SetMixin sm = new SetMixin(nodeState, mixinNames);
        return sm;
    }
}