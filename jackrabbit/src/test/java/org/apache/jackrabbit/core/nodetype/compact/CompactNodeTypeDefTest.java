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
package org.apache.jackrabbit.core.nodetype.compact;

import junit.framework.TestCase;
import org.apache.jackrabbit.core.nodetype.NodeDef;
import org.apache.jackrabbit.core.nodetype.NodeDefImpl;
import org.apache.jackrabbit.core.nodetype.NodeTypeDef;
import org.apache.jackrabbit.core.nodetype.NodeTypeDefDiff;
import org.apache.jackrabbit.core.nodetype.PropDef;
import org.apache.jackrabbit.core.nodetype.PropDefImpl;
import org.apache.jackrabbit.core.nodetype.ValueConstraint;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.util.name.NamespaceMapping;

import javax.jcr.PropertyType;
import javax.jcr.version.OnParentVersionAction;
import java.io.FileReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

public class CompactNodeTypeDefTest extends TestCase {
    private static final String TEST_FILE = "applications/test/cnd-reader-test-input.cnd";
    private static final String NS_PREFIX = "ex";
    private static final String NS_URI = "http://apache.org/incubator/jackrabbit/example";

    private static final QName NODE_TYPE_NAME = new QName(NS_URI, "NodeType");
    private static final QName PARENT_NODE_TYPE_1 = new QName(NS_URI, "ParentNodeType1");
    private static final QName PARENT_NODE_TYPE_2 = new QName(NS_URI, "ParentNodeType2");
    private static final QName[] SUPERTYPES = new QName[]{PARENT_NODE_TYPE_1, PARENT_NODE_TYPE_2};

    private static final QName PROPERTY_NAME = new QName(NS_URI, "property");
    private static final long DEFAULT_VALUE_1 = 1;
    private static final long DEFAULT_VALUE_2 = 2;
    private static final String VALUE_CONSTRAINT = "[1,10]";

    private static final QName CHILD_NODE_NAME = new QName(NS_URI, "node");
    private static final QName REQUIRED_NODE_TYPE_1 = new QName(NS_URI, "RequiredNodeType1");
    private static final QName REQUIRED_NODE_TYPE_2 = new QName(NS_URI, "RequiredNodeType2");
    private static final QName[] REQUIRED_NODE_TYPES = new QName[]{REQUIRED_NODE_TYPE_1, REQUIRED_NODE_TYPE_2};

    private NodeTypeDef modelNodeTypeDef;

    protected void setUp() throws Exception {
        NamespaceMapping namespaceMapping = new NamespaceMapping();
        namespaceMapping.setMapping(NS_PREFIX, NS_URI);
        InternalValue dv1 = InternalValue.create(DEFAULT_VALUE_1);
        InternalValue dv2 = InternalValue.create(DEFAULT_VALUE_2);
        ValueConstraint vc = ValueConstraint.create(PropertyType.LONG, VALUE_CONSTRAINT, namespaceMapping);
        InternalValue[] defaultValues = new InternalValue[]{dv1, dv2};
        ValueConstraint[] valueConstraints = new ValueConstraint[]{vc};

        PropDefImpl pd = new PropDefImpl();
        pd.setName(PROPERTY_NAME);
        pd.setRequiredType(PropertyType.LONG);
        pd.setMandatory(true);
        pd.setAutoCreated(true);
        pd.setProtected(true);
        pd.setMultiple(true);
        pd.setOnParentVersion(OnParentVersionAction.VERSION);
        pd.setDefaultValues(defaultValues);
        pd.setValueConstraints(valueConstraints);
        pd.setDeclaringNodeType(NODE_TYPE_NAME);

        NodeDefImpl nd = new NodeDefImpl();
        nd.setName(CHILD_NODE_NAME);
        nd.setRequiredPrimaryTypes(REQUIRED_NODE_TYPES);
        nd.setDefaultPrimaryType(REQUIRED_NODE_TYPE_1);
        nd.setMandatory(true);
        nd.setAutoCreated(true);
        nd.setProtected(true);
        nd.setAllowsSameNameSiblings(true);
        nd.setOnParentVersion(OnParentVersionAction.VERSION);
        nd.setDeclaringNodeType(NODE_TYPE_NAME);

        modelNodeTypeDef = new NodeTypeDef();
        modelNodeTypeDef.setName(NODE_TYPE_NAME);
        modelNodeTypeDef.setSupertypes(SUPERTYPES);
        modelNodeTypeDef.setOrderableChildNodes(true);
        modelNodeTypeDef.setMixin(true);
        modelNodeTypeDef.setPrimaryItemName(PROPERTY_NAME);
        modelNodeTypeDef.setPropertyDefs(new PropDef[]{pd});
        modelNodeTypeDef.setChildNodeDefs(new NodeDef[]{nd});
    }

    public void testCompactNodeTypeDef() throws Exception {

        // Read in node type def from test file
        CompactNodeTypeDefReader cndReader = new CompactNodeTypeDefReader(new FileReader(TEST_FILE), TEST_FILE);
        List ntdList = cndReader.getNodeTypeDefs();
        NamespaceMapping nsm = cndReader.getNamespaceMapping();
        NodeTypeDef ntd = (NodeTypeDef)ntdList.get(0);

        // Test CND Reader by comparing imported NTD with model NTD.
        NodeTypeDefDiff diff = NodeTypeDefDiff.create(modelNodeTypeDef, ntd);
        if (diff.isModified()){
            fail("Imported node type definition is not identical to model definition");
        }

        // Put imported node type def back into CND form with CND writer
        StringWriter sw = new StringWriter();
        CompactNodeTypeDefWriter.write(ntdList, nsm, sw);

        // Rerun the reader on the product of the writer
        cndReader = new CompactNodeTypeDefReader(new StringReader(sw.toString()), TEST_FILE);
        ntdList = cndReader.getNodeTypeDefs();
        ntd = (NodeTypeDef)ntdList.get(0);

        diff = NodeTypeDefDiff.create(modelNodeTypeDef, ntd);
        if (diff.isModified()){
            fail("Exported node type definition was not successfully read back in");
        }
    }
}
