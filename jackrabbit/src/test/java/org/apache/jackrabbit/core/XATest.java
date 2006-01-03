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

import org.apache.jackrabbit.test.AbstractJCRTest;

import javax.jcr.Repository;
import javax.jcr.Node;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Session;
import javax.jcr.lock.Lock;
import javax.transaction.UserTransaction;
import javax.transaction.RollbackException;

/**
 * <code>XATest</code> contains the test cases for the methods
 * inside {@link org.apache.jackrabbit.core.XASession}.
 */
public class XATest extends AbstractJCRTest {

    /**
     * @see junit.framework#runTest
     *
     * Make sure that tested repository supports transactions
     */
    protected void runTest() throws Throwable {
        Repository rep = helper.getRepository();
        if (rep.getDescriptor(Repository.OPTION_TRANSACTIONS_SUPPORTED) != null) {
            super.runTest();
        }
    }

    public void testCheckin() throws Exception {
        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // add node and save
        Node n = testRootNode.addNode(nodeName1, testNodeType);
        n.addMixin(mixVersionable);
        testRootNode.save();

        n.checkin();

        assertFalse("Node must be checked-in", n.isCheckedOut());

        // commit
        utx.commit();

        assertFalse("Node must be checked-in", n.isCheckedOut());
    }

    /**
     * Add a node inside a transaction and commit changes. Make sure
     * node exists for other sessions only after commit.
     * @throws Exception
     */
    public void testAddNodeCommit() throws Exception {
        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // add node and save
        Node n = testRootNode.addNode(nodeName1, testNodeType);
        n.addMixin(mixReferenceable);
        testRootNode.save();

        // assertion: node exists in this session
        try {
            superuser.getNodeByUUID(n.getUUID());
        } catch (ItemNotFoundException e) {
            fail("New node not visible after save()");
        }

        // assertion: node does not exist in other session
        Session otherSuperuser = helper.getSuperuserSession();

        try {
            otherSuperuser.getNodeByUUID(n.getUUID());
            fail("Uncommitted node visible for other session");
        } catch (ItemNotFoundException e) {
            /* expected */
        }

        // commit
        utx.commit();

        // assertion: node exists in this session
        try {
            otherSuperuser.getNodeByUUID(n.getUUID());
        } catch (ItemNotFoundException e) {
            fail("Committed node not visible in this session");
        }

        // assertion: node also exists in other session
        try {
            otherSuperuser.getNodeByUUID(n.getUUID());
        } catch (ItemNotFoundException e) {
            fail("Committed node not visible in other session");
        }

        // logout
        otherSuperuser.logout();
    }

    /**
     * Set a property inside a transaction and commit changes. Make sure
     * property exists for other sessions only after commit.
     * @throws Exception
     */
    public void testSetPropertyCommit() throws Exception {
        // prerequisite: non-existing property
        if (testRootNode.hasProperty(propertyName1)) {
            testRootNode.getProperty(propertyName1).remove();
            testRootNode.save();
        }

        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // set property and save
        testRootNode.setProperty(propertyName1, "0");
        testRootNode.save();

        // assertion: property exists in this session
        assertTrue(testRootNode.hasProperty(propertyName1));

        // assertion: property does not exist in other session
        Session otherSuperuser = helper.getSuperuserSession();
        Node otherRootNode = otherSuperuser.getRootNode().getNode(testPath);
        assertFalse(otherRootNode.hasProperty(propertyName1));

        // commit
        utx.commit();

        // assertion: property exists in this session
        assertTrue(testRootNode.hasProperty(propertyName1));

        // assertion: property also exists in other session
        assertTrue(otherRootNode.hasProperty(propertyName1));

        // logout
        otherSuperuser.logout();
    }

    /**
     * @throws Exception
     */
    public void testAddAndSetProperty() throws Exception {
        // prerequisite: non-existing property
        if (testRootNode.hasProperty(propertyName1)) {
            testRootNode.getProperty(propertyName1).remove();
            testRootNode.save();
        }

        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // 'add' property and save
        testRootNode.setProperty(propertyName1, "0");
        testRootNode.save();

        // 'modify' property and save
        testRootNode.setProperty(propertyName1, "1");
        testRootNode.save();

        // commit
        utx.commit();

        // check property value
        Session otherSuperuser = helper.getSuperuserSession();
        Node n = (Node) otherSuperuser.getItem(testRootNode.getPath());
        assertEquals(n.getProperty(propertyName1).getString(), "1");
        otherSuperuser.logout();
    }

    /**
     * @throws Exception
     */
    public void testPropertyIsNew() throws Exception {
        // prerequisite: non-existing property
        if (testRootNode.hasProperty(propertyName1)) {
            testRootNode.getProperty(propertyName1).remove();
            testRootNode.save();
        }

        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // 'add' property and save
        testRootNode.setProperty(propertyName1, "0");

        assertTrue("New property must be new.", testRootNode.getProperty(propertyName1).isNew());

        testRootNode.save();

        assertFalse("Saved property must not be new.", testRootNode.getProperty(propertyName1).isNew());

        // commit
        utx.commit();
    }

    /**
     * @throws Exception
     */
    public void testNewNodeIsLocked() throws Exception {
        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // add node and save
        Node n = testRootNode.addNode(nodeName1, testNodeType);
        testRootNode.save();

        assertFalse("New node must not be locked.", n.isLocked());

        // commit
        utx.commit();
    }

    /**
     * @throws Exception
     */
    public void testPropertyIsModified() throws Exception {
        // prerequisite: existing property
        testRootNode.setProperty(propertyName1, "0");
        testRootNode.save();

        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // 'add' property and save
        testRootNode.setProperty(propertyName1, "1");

        assertTrue("Unsaved property must be modified.", testRootNode.getProperty(propertyName1).isModified());

        testRootNode.save();

        assertFalse("Saved property must not be modified.", testRootNode.getProperty(propertyName1).isModified());

        // commit
        utx.commit();
    }

    /**
     * @throws Exception
     */
    public void testDeleteAndAddProperty() throws Exception {
        // prerequisite: existing property
        testRootNode.setProperty(propertyName1, "0");
        testRootNode.save();

        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // 'delete' property and save
        testRootNode.getProperty(propertyName1).remove();
        testRootNode.save();

        // 'add' property and save
        testRootNode.setProperty(propertyName1, "1");
        testRootNode.save();

        // commit
        utx.commit();

        // check property value
        Session otherSuperuser = helper.getSuperuserSession();
        Node n = (Node) otherSuperuser.getItem(testRootNode.getPath());
        assertEquals(n.getProperty(propertyName1).getString(), "1");
        otherSuperuser.logout();
    }

    /**
     * @throws Exception
     */
    public void testModifyAndDeleteProperty() throws Exception {
        // prerequisite: existing property
        testRootNode.setProperty(propertyName1, "0");
        testRootNode.save();

        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // 'modify' property and save
        testRootNode.setProperty(propertyName1, "1");
        testRootNode.save();

        // 'delete' property and save
        testRootNode.getProperty(propertyName1).remove();
        testRootNode.save();

        // commit
        utx.commit();

        // check property value
        Session otherSuperuser = helper.getSuperuserSession();
        Node n = (Node) otherSuperuser.getItem(testRootNode.getPath());
        assertFalse("Property must be deleted.", n.hasProperty(propertyName1));
        otherSuperuser.logout();
    }

    /**
     * @throws Exception
     */
    public void testAddAndDeleteProperty() throws Exception {
        // prerequisite: non-existing property
        if (testRootNode.hasProperty(propertyName1)) {
            testRootNode.getProperty(propertyName1).remove();
            testRootNode.save();
        }

        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // 'add' property and save
        testRootNode.setProperty(propertyName1, "1");
        testRootNode.save();

        // 'delete' property and save
        testRootNode.getProperty(propertyName1).remove();
        testRootNode.save();

        // commit
        utx.commit();

        // check property value
        Session otherSuperuser = helper.getSuperuserSession();
        Node n = (Node) otherSuperuser.getItem(testRootNode.getPath());
        assertFalse("Property must be deleted.", n.hasProperty(propertyName1));
        otherSuperuser.logout();
    }

    /**
     * Add a node inside a transaction and rollback changes.
     * @throws Exception
     */
    public void testAddNodeRollback() throws Exception {
        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // add node and save
        Node n = testRootNode.addNode(nodeName1, testNodeType);
        n.addMixin(mixReferenceable);
        testRootNode.save();

        // assertion: node exists in this session
        String uuid = n.getUUID();

        try {
            superuser.getNodeByUUID(uuid);
        } catch (ItemNotFoundException e) {
            fail("New node not visible after save()");
        }

        // rollback
        utx.rollback();

        // assertion: node does not exist in this session
        try {
            superuser.getNodeByUUID(uuid);
            fail("Node still visible after rollback()");
        } catch (ItemNotFoundException e) {
            /* expected */
        }
    }

    /**
     * Set a property inside a transaction and rollback changes.
     * @throws Exception
     */
    public void testSetPropertyRollback() throws Exception {
        // prerequisite: non-existing property
        if (testRootNode.hasProperty(propertyName1)) {
            testRootNode.getProperty(propertyName1).remove();
            testRootNode.save();
        }

        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // set property and save
        testRootNode.setProperty(propertyName1, "0");
        testRootNode.save();

        // assertion: property exists in this session
        assertTrue(testRootNode.hasProperty(propertyName1));

        // rollback
        utx.rollback();

        // assertion: property does not exist in this session
        assertFalse(testRootNode.hasProperty(propertyName1));
    }

    /**
     * Remove a node inside a transaction and rollback changes. Check
     * that the node reference may again be used after having rolled
     * back changes.
     * @throws Exception
     */
    public void testRemoveNodeRollback() throws Exception {
        // prerequisite: existing node
        Node n1 = testRootNode.addNode(nodeName1, testNodeType);
        n1.addMixin(mixReferenceable);
        testRootNode.save();

        String uuid = n1.getUUID();

        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // remove node and save
        Node n2 = superuser.getNodeByUUID(uuid);
        n2.remove();
        testRootNode.save();

        // assertion: node no longer exists
        try {
            superuser.getNodeByUUID(uuid);
            fail("Removed node still exists after save()");
        } catch (ItemNotFoundException e) {
            /* expected */
        }

        // rollback
        utx.rollback();

        // assertion: node exists again
        try {
            superuser.getNodeByUUID(uuid);
        } catch (ItemNotFoundException e) {
            fail("Removed node not visible after rollback()");
        }
    }

    /**
     * Remove a property inside a transaction and rollback changes.
     * Check that the property reference may again be used after
     * having rolled back changes.
     * @throws Exception
     */
    public void testRemovePropertyRollback() throws Exception {
        // prerequisite: existing property
        if (!testRootNode.hasProperty(propertyName1)) {
            testRootNode.setProperty(propertyName1, "0");
            testRootNode.save();
        }

        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // remove property and save
        testRootNode.getProperty(propertyName1).remove();
        testRootNode.save();

        // assertion: property no longer exists
        assertFalse(testRootNode.hasProperty(propertyName1));

        // rollback
        utx.rollback();

        // assertion: property exists and reference valid
        assertTrue(testRootNode.hasProperty(propertyName1));
    }

    /**
     * Add reference to some node in one session while removing
     * the node in another.
     * @throws Exception
     */
    public void testAddReference() throws Exception {
        // add two nodes, second one referenceable
        Node n1 = testRootNode.addNode(nodeName1);
        Node n2 = testRootNode.addNode(nodeName2);
        n2.addMixin(mixReferenceable);
        testRootNode.save();

        // get user transaction object
        UserTransaction utx = new UserTransactionImpl(superuser);

        // start transaction
        utx.begin();

        // add reference and save
        n1.setProperty(propertyName1, n2);
        testRootNode.save();

        // remove referenced node in other session
        Session otherSuperuser = helper.getSuperuserSession();
        Node otherRootNode = otherSuperuser.getRootNode().getNode(testPath);
        otherSuperuser.getNodeByUUID(n2.getUUID()).remove();
        otherRootNode.save();

        // assertion: commit must fail since integrity violated
        try {
            utx.commit();
            fail("Commit succeeds with violated integrity");
        } catch (RollbackException e) {
            /* expected */
        }

        // logout
        otherSuperuser.logout();
    }

    /**
     * Test locking a node in one session. Verify that node is not locked
     * in other session until commit.
     * @throws Exception
     */
    public void testLockCommit() throws Exception {
        Session other = helper.getSuperuserSession();

        // add node that is both lockable and referenceable, save
        Node n = testRootNode.addNode(nodeName1);
        n.addMixin(mixLockable);
        n.addMixin(mixReferenceable);
        testRootNode.save();

        // reference node in second session
        Node nOther = other.getNodeByUUID(n.getUUID());

        // verify node is not locked in either session
        assertFalse("Node not locked in session 1", n.isLocked());
        assertFalse("Node not locked in session 2", nOther.isLocked());

        // get user transaction object, start and lock node
        UserTransaction utx = new UserTransactionImpl(superuser);
        utx.begin();
        n.lock(false, true);

        // verify node is locked in first session only
        assertTrue("Node locked in session 1", n.isLocked());
        assertFalse("Node not locked in session 2", nOther.isLocked());

        // commit in first session
        utx.commit();

        // verify node is locked in both sessions
        assertTrue("Node locked in session 1", n.isLocked());
        assertTrue("Node locked in session 2", nOther.isLocked());

        // logout
        other.logout();
    }

    /**
     * Test locking a node in one session. Verify that node is not locked
     * in session after rollback.
     * @throws Exception
     */
    public void testLockRollback() throws Exception {
        Session other = helper.getSuperuserSession();

        // add node that is both lockable and referenceable, save
        Node n = testRootNode.addNode(nodeName1);
        n.addMixin(mixLockable);
        n.addMixin(mixReferenceable);
        testRootNode.save();

        // reference node in second session
        Node nOther = other.getNodeByUUID(n.getUUID());

        // verify node is not locked in either session
        assertFalse("Node not locked in session 1", n.isLocked());
        assertFalse("Node not locked in session 2", nOther.isLocked());

        // get user transaction object, start and lock node
        UserTransaction utx = new UserTransactionImpl(superuser);
        utx.begin();
        n.lock(false, true);

        // verify node is locked in first session only
        assertTrue("Node locked in session 1", n.isLocked());
        assertFalse("Node not locked in session 2", nOther.isLocked());

        // rollback in first session
        utx.rollback();

        // verify node is not locked in either session
        assertFalse("Node not locked in session 1", n.isLocked());
        assertFalse("Node not locked in session 2", nOther.isLocked());

        // logout
        other.logout();
    }

    /**
     * Test locking a node inside a transaction that has been locked in another
     * session, which leads to a failure when committing.
     * @throws Exception
     */
    public void testLockTwice() throws Exception {
        Session other = helper.getSuperuserSession();

        // add node that is both lockable and referenceable, save
        Node n = testRootNode.addNode(nodeName1);
        n.addMixin(mixLockable);
        n.addMixin(mixReferenceable);
        testRootNode.save();

        // reference node in second session
        Node nOther = other.getNodeByUUID(n.getUUID());

        // verify node is not locked in either session
        assertFalse("Node not locked in session 1", n.isLocked());
        assertFalse("Node not locked in session 2", nOther.isLocked());

        // get user transaction object, start and lock node
        UserTransaction utx = new UserTransactionImpl(superuser);
        utx.begin();
        n.lock(false, true);

        // lock node in non-transactional session, too
        nOther.lock(false, true);

        // verify node is locked in both sessions
        assertTrue("Node locked in session 1", n.isLocked());
        assertTrue("Node locked in session 2", nOther.isLocked());

        // assertion: commit must fail since node has already been locked
        try {
            utx.commit();
            fail("Commit succeeds with double locking");
        } catch (RollbackException e) {
            /* expected */
        }

        // verify node is locked in both sessions
        assertTrue("Node locked in session 1", n.isLocked());
        assertTrue("Node locked in session 2", nOther.isLocked());

        // logout
        other.logout();
    }

    /**
     * Test locking a new node inside a transaction.
     * @throws Exception
     */
    public void testLockNewNode() throws Exception {
        // get user transaction object, start
        UserTransaction utx = new UserTransactionImpl(superuser);
        utx.begin();

        // add node that is both lockable and referenceable, save
        Node n = testRootNode.addNode(nodeName1);
        n.addMixin(mixLockable);
        n.addMixin(mixReferenceable);
        testRootNode.save();

        // lock this new node
        n.lock(false, true);

        // commit
        utx.commit();
    }

    /**
     * Test locking/unlocking a node inside a transaction which should be a
     * no-op.
     * @throws Exception
     */
    public void testLockUnlock() throws Exception {
        // add node that is both lockable and referenceable, save
        Node n = testRootNode.addNode(nodeName1);
        n.addMixin(mixLockable);
        n.addMixin(mixReferenceable);
        testRootNode.save();

        // verify node is not locked in this session
        assertFalse("Node not locked", n.isLocked());

        // get user transaction object, start and lock node
        UserTransaction utx = new UserTransactionImpl(superuser);
        utx.begin();
        n.lock(false, true);

        // verify node is locked
        assertTrue("Node locked", n.isLocked());

        // unlock node
        n.unlock();

        // commit
        utx.commit();

        // verify node is not locked
        assertFalse("Node not locked", n.isLocked());
    }

    /**
     * Test correct behaviour of {@link javax.jcr.lock.Lock} inside a
     * transaction.
     * @throws Exception
     */
    public void testLockBehaviour() throws Exception {
        // add node that is both lockable and referenceable, save
        Node n = testRootNode.addNode(nodeName1);
        n.addMixin(mixLockable);
        n.addMixin(mixReferenceable);
        testRootNode.save();

        // get user transaction object, start and lock node
        UserTransaction utx = new UserTransactionImpl(superuser);
        utx.begin();
        Lock lock = n.lock(false, true);

        // verify lock is live
        assertTrue("Lock live", lock.isLive());

        // rollback
        utx.rollback();

        // verify lock is not live anymore
        assertFalse("Lock not live", lock.isLive());
    }

    /**
     * Test correct behaviour of {@link javax.jcr.lock.Lock} inside a
     * transaction.
     * @throws Exception
     */
    public void testLockBehaviour2() throws Exception {
        // add node that is both lockable and referenceable, save
        Node n = testRootNode.addNode(nodeName1);
        n.addMixin(mixLockable);
        n.addMixin(mixReferenceable);
        testRootNode.save();

        Lock lock = n.lock(false, true);

        // get user transaction object, start
        UserTransaction utx = new UserTransactionImpl(superuser);
        utx.begin();

        // verify lock is live
        assertTrue("Lock live", lock.isLive());

        // unlock
        n.unlock();

        // verify lock is no longer live
        assertFalse("Lock not live", lock.isLive());

        // rollback
        utx.rollback();

        // verify lock is live again
        assertTrue("Lock live", lock.isLive());
    }
}
