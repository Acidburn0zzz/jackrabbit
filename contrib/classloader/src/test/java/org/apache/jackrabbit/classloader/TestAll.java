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
package org.apache.jackrabbit.classloader;

import junit.framework.Test;
import junit.framework.TestSuite;

public class TestAll {

    public static Test suite() {
        TestSuite suite = new TestSuite("Test for Jackrabbit Class Loader");
        //$JUnit-BEGIN$
        suite.addTest(new AbstractClassLoaderTest("repositoryStart"));
        suite.addTestSuite(PatternPathTest.class);
        suite.addTestSuite(ExpandingArchiveClassPathEntryTest.class);
        suite.addTestSuite(DynamicRepositoryClassLoaderTest.class);
        suite.addTest(new AbstractClassLoaderTest("repositoryStop"));
        //$JUnit-END$
        return suite;
    }
}
