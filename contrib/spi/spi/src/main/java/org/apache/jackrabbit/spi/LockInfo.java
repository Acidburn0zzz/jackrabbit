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
package org.apache.jackrabbit.spi;

/**
 * <code>LockInfo</code>...
 */
public interface LockInfo {

    /**
     * Id of the node this <code>LockInfo</code> was requested for. Note, that
     * the id does not represent the id of the lock-holding node.
     *
     * @return
     */
    // TODO: review if this is needed
    public NodeId getNodeId();

    public String getLockToken();

    public String getOwner();

    public boolean isDeep();
}