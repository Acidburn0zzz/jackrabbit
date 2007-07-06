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
package org.apache.jackrabbit.spi.commons;

import org.apache.jackrabbit.spi.Batch;
import org.apache.jackrabbit.spi.NodeId;
import org.apache.jackrabbit.spi.QValue;
import org.apache.jackrabbit.spi.PropertyId;
import org.apache.jackrabbit.spi.ItemId;
import org.apache.jackrabbit.spi.SessionInfo;
import org.apache.jackrabbit.name.QName;

import javax.jcr.RepositoryException;
import javax.jcr.ValueFormatException;
import javax.jcr.PathNotFoundException;
import javax.jcr.AccessDeniedException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.lock.LockException;
import javax.jcr.version.VersionException;
import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * <code>SerializableBatch</code> implements a serializable SPI Batch, which
 * simply records all calls and replays them when asked for. The client of
 * this batch must ensure that the passed {@link QValue} instances are
 * serializable, otherwise the serializing the <code>Batch</code> will fail!
 */
public class SerializableBatch implements Batch, Serializable {

    private List recording = new ArrayList();

    private final ItemId itemId;

    /**
     * Creates a new <code>SerializableBatch</code>.
     *
     * @param itemId the id of the item where save was called. To indicate that
     *               save was called on the session, the id of the root node
     *               must be passed.
     */
    public SerializableBatch(ItemId itemId) {
        this.itemId = itemId;
    }

    /**
     * @return the item id where save was called for this batch.
     */
    public ItemId getSaveTarget() {
        return itemId;
    }

    /**
     * Replays this batch on the given <code>batch</code>. For a description of
     * the exception see {@link org.apache.jackrabbit.spi.RepositoryService#submit(Batch)}.
     *
     * @param batch the target batch.
     */
    public void replay(Batch batch) throws PathNotFoundException, ItemNotFoundException, NoSuchNodeTypeException, ValueFormatException, VersionException, LockException, ConstraintViolationException, AccessDeniedException, UnsupportedRepositoryOperationException, RepositoryException {
        for (Iterator it = recording.iterator(); it.hasNext(); ) {
            ((Operation) it.next()).replay(batch);
        }
    }

    //----------------------------< Batch >-------------------------------------

    public void addNode(NodeId parentId,
                        QName nodeName,
                        QName nodetypeName,
                        String uuid) {
        recording.add(new AddNode(parentId, nodeName, nodetypeName, uuid));
    }

    public void addProperty(NodeId parentId, QName propertyName, QValue value) {
        recording.add(new AddProperty(parentId, propertyName,
                new QValue[]{value}, false));
    }

    public void addProperty(NodeId parentId,
                            QName propertyName,
                            QValue[] values) {
        recording.add(new AddProperty(parentId, propertyName, values, true));
    }

    public void setValue(PropertyId propertyId, QValue value) {
        recording.add(new SetValue(propertyId, new QValue[]{value}, false));
    }

    public void setValue(PropertyId propertyId, QValue[] values) {
        recording.add(new SetValue(propertyId, values, true));
    }

    public void remove(ItemId itemId) {
        recording.add(new Remove(itemId));
    }

    public void reorderNodes(NodeId parentId,
                             NodeId srcNodeId,
                             NodeId beforeNodeId) {
        recording.add(new ReorderNodes(parentId, srcNodeId, beforeNodeId));
    }

    public void setMixins(NodeId nodeId, QName[] mixinNodeTypeIds) {
        recording.add(new SetMixins(nodeId, mixinNodeTypeIds));
    }

    public void move(NodeId srcNodeId,
                     NodeId destParentNodeId,
                     QName destName) {
        recording.add(new Move(srcNodeId, destParentNodeId, destName));
    }

    //----------------------------< internal >----------------------------------

    public interface Operation extends Serializable {

        /**
         * Replays this operation on the given <code>batch</code>.
         *
         * @param batch the batch.
         * @throws RepositoryException if an error occurs replaying the
         *                             operation.
         */
        public void replay(Batch batch) throws RepositoryException;
    }

    private static class AddNode implements Operation {

        private final NodeId parentId;

        private final QName nodeName;

        private final QName nodetypeName;

        private final String uuid;

        AddNode(NodeId parentId, QName nodeName, QName nodetypeName, String uuid) {
            this.parentId = parentId;
            this.nodeName = nodeName;
            this.nodetypeName = nodetypeName;
            this.uuid = uuid;
        }

        /**
         * {@inheritDoc}
         */
        public void replay(Batch batch) throws RepositoryException {
            batch.addNode(parentId, nodeName, nodetypeName, uuid);
        }
    }

    private static class AddProperty implements Operation {

        private final NodeId parentId;

        private final QName propertyName;

        private final QValue[] values;

        private final boolean isMultiValued;

        AddProperty(NodeId parentId, QName propertyName,
                    QValue[] values, boolean isMultiValued) {
            this.parentId = parentId;
            this.propertyName = propertyName;
            this.values = values;
            this.isMultiValued = isMultiValued;
        }

        /**
         * {@inheritDoc}
         */
        public void replay(Batch batch) throws RepositoryException {
            if (isMultiValued) {
                batch.addProperty(parentId, propertyName, values);
            } else {
                batch.addProperty(parentId, propertyName, values[0]);
            }
        }
    }

    private static class SetValue implements Operation {

        private final PropertyId propertyId;

        private final QValue[] values;

        private final boolean isMultiValued;

        SetValue(PropertyId propertyId, QValue[] values, boolean isMultiValued) {
            this.propertyId = propertyId;
            this.values = values;
            this.isMultiValued = isMultiValued;
        }

        /**
         * {@inheritDoc}
         */
        public void replay(Batch batch) throws RepositoryException {
            if (isMultiValued) {
                batch.setValue(propertyId, values);
            } else {
                batch.setValue(propertyId, values[0]);
            }
        }
    }

    private static class Remove implements Operation {

        private final ItemId itemId;

        Remove(ItemId itemId) {
            this.itemId = itemId;
        }

        /**
         * {@inheritDoc}
         */
        public void replay(Batch batch) throws RepositoryException {
            batch.remove(itemId);
        }
    }

    private static class ReorderNodes implements Operation {

        private final NodeId parentId;

        private final NodeId srcNodeId;

        private final NodeId beforeNodeId;

        ReorderNodes(NodeId parentId, NodeId srcNodeId, NodeId beforeNodeId) {
            this.parentId = parentId;
            this.srcNodeId = srcNodeId;
            this.beforeNodeId = beforeNodeId;
        }

        /**
         * {@inheritDoc}
         */
        public void replay(Batch batch) throws RepositoryException {
            batch.reorderNodes(parentId, srcNodeId, beforeNodeId);
        }
    }

    private static class SetMixins implements Operation {

        private final NodeId nodeId;

        private final QName[] mixinNodeTypeIds;

        SetMixins(NodeId nodeId, QName[] mixinNodeTypeIds) {
            this.nodeId = nodeId;
            this.mixinNodeTypeIds = mixinNodeTypeIds;
        }

        /**
         * {@inheritDoc}
         */
        public void replay(Batch batch) throws RepositoryException {
            batch.setMixins(nodeId, mixinNodeTypeIds);
        }
    }

    private static class Move implements Operation {

        private final NodeId srcNodeId;

        private final NodeId destParentNodeId;

        private final QName destName;

        Move(NodeId srcNodeId, NodeId destParentNodeId, QName destName) {
            this.srcNodeId = srcNodeId;
            this.destParentNodeId = destParentNodeId;
            this.destName = destName;
        }

        /**
         * {@inheritDoc}
         */
        public void replay(Batch batch) throws RepositoryException {
            batch.move(srcNodeId, destParentNodeId, destName);
        }
    }
}
