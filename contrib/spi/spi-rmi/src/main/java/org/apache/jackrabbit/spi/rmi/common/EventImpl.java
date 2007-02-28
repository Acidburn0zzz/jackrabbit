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
package org.apache.jackrabbit.spi.rmi.common;

import org.apache.jackrabbit.spi.Event;
import org.apache.jackrabbit.spi.ItemId;
import org.apache.jackrabbit.spi.NodeId;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.QName;

import java.io.Serializable;

/**
 * <code>EventImpl</code> implements a serializable SPI
 * {@link org.apache.jackrabbit.spi.Event}.
 */
public class EventImpl implements Event, Serializable {

    /**
     * The SPI event type.
     * @see Event
     */
    private final int type;

    /**
     * The path of the affected item.
     */
    private final Path path;

    /**
     * The id of the affected item.
     */
    private final ItemId itemId;

    /**
     * The id of the affected item.
     */
    private final NodeId parentId;

    /**
     * The name of the primary node type of the 'associated' node of this event.
     */
    private final QName primaryNodeTypeName;

    /**
     * The names of the mixin types of the 'associated' node of this event.
     */
    private final QName[] mixinTypeNames;

    /**
     * The user ID connected with this event.
     */
    private final String userId;

    /**
     * Creates a new serializable event.
     */
    public EventImpl(int type, Path path, ItemId itemId, NodeId parentId,
                     QName primaryNodeTypeName, QName[] mixinTypeNames,
                     String userId) {
        this.type = type;
        this.path = path;
        this.itemId = itemId;
        this.parentId = parentId;
        this.primaryNodeTypeName = primaryNodeTypeName;
        this.mixinTypeNames = mixinTypeNames;
        this.userId = userId;
    }

    /**
     * {@inheritDoc}
     */
    public int getType() {
        return type;
    }

    /**
     * {@inheritDoc}
     */
    public Path getQPath() {
        return path;
    }

    /**
     * {@inheritDoc}
     */
    public ItemId getItemId() {
        return itemId;
    }

    /**
     * {@inheritDoc}
     */
    public NodeId getParentId() {
        return parentId;
    }

    /**
     * {@inheritDoc}
     */
    public QName getPrimaryNodeTypeName() {
        return primaryNodeTypeName;
    }

    /**
     * {@inheritDoc}
     */
    public QName[] getMixinTypeNames() {
        QName[] mixins = new QName[mixinTypeNames.length];
        System.arraycopy(mixinTypeNames, 0, mixins, 0, mixinTypeNames.length);
        return mixins;
    }

    /**
     * {@inheritDoc}
     */
    public String getUserID() {
        return userId;
    }
}
