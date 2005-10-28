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
package org.apache.jackrabbit.core.state.bdb;

import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.bind.tuple.TupleOutput;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jackrabbit.core.state.NodeReferences;
import org.apache.jackrabbit.core.state.NodeReferencesId;
import org.apache.jackrabbit.core.state.util.Serializer;

public class NodeReferencesTupleBinding extends TupleBinding {

    private Log log = LogFactory.getLog(NodeReferencesTupleBinding.class);

    private NodeReferencesId id;

    public NodeReferencesTupleBinding(NodeReferencesId id) {
        this.id = id;
    }

    public NodeReferencesTupleBinding() {
    }

    public Object entryToObject(TupleInput in) {

        NodeReferences refs = new NodeReferences(id);

        try {
            Serializer.deserialize(refs, in);
        } catch (Exception e) {
            // since the TupleInput methods do not throw any
            // exceptions the above call should neither...
            String msg = "error while deserializing node references";
            log.debug(msg);
            throw new RuntimeException(msg, e);
        }

        return refs;
    }

    public void objectToEntry(Object o, TupleOutput out) {
        try {
            Serializer.serialize((NodeReferences) o, out);
        } catch (Exception e) {
            // since the TupleOutput methods do not throw any
            // exceptions the above call should neither...
            String msg = "error while serializing node references";
            log.debug(msg);
            throw new RuntimeException(msg, e);
        }
    }
}
