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
package org.apache.jackrabbit.core.state;

import org.apache.jackrabbit.core.ItemId;
import org.apache.commons.collections.SequencedHashMap;

import java.util.Map;
import java.util.Iterator;

/**
 * Registers changes made to states and references and consolidates
 * empty changes.
 */
public class ChangeLog {

    /**
     * Added states
     */
    private final Map addedStates = new SequencedHashMap();

    /**
     * Modified states
     */
    private final Map modifiedStates = new SequencedHashMap();

    /**
     * Deleted states
     */
    private final Map deletedStates = new SequencedHashMap();

    /**
     * Modified references
     */
    private final Map modifiedRefs = new SequencedHashMap();

    /**
     * A state has been added
     * @param state state that has been added
     */
    public void added(ItemState state) {
        addedStates.put(state.getId(), state);
    }

    /**
     * A state has been modified. If the state is not a new state
     * (not in the collection of added ones), then disconnect
     * the local state from its underlying shared state and add
     * it to the modified states collection.
     * @param state state that has been modified
     */
    public void modified(ItemState state) {
        if (!addedStates.containsKey(state.getId())) {
            state.disconnect();
            modifiedStates.put(state.getId(), state);
        }
    }

    /**
     * A state has been deleted. If the state is not a new state
     * (not in the collection of added ones), then disconnect
     * the local state from its underlying shared state, remove
     * it from the modified states collection and add it to the
     * deleted states collection.
     * @param state state that has been deleted
     */
    public void deleted(ItemState state) {
        if (addedStates.remove(state.getId()) == null) {
            state.disconnect();
            modifiedStates.remove(state.getId());
            deletedStates.put(state.getId(), state);
        }
    }

    /**
     * A references has been modified
     * @param refs refs that has been modified
     */
    public void modified(NodeReferences refs) {
        modifiedRefs.put(refs.getTargetId(), refs);
    }

    /**
     * Return an item state given its id. Returns <code>null</code>
     * if the item state is neither in the added nor in the modified
     * section. Throws a <code>NoSuchItemStateException</code> if
     * the item state is in the deleted section.
     * @return item state or <code>null</code>
     * @throws NoSuchItemStateException if the item has been deleted
     */
    public ItemState get(ItemId id) throws NoSuchItemStateException {
        ItemState state = (ItemState) addedStates.get(id);
        if (state == null) {
            state = (ItemState) modifiedStates.get(id);
            if (state == null) {
                if (deletedStates.containsKey(id)) {
                    throw new NoSuchItemStateException(
                            "State has been marked destroyed: " + id);
                }
            }
        }
        return state;
    }

    /**
     * Return a flag indicating whether a given item state exists.
     * @return <code>true</code> if item state exists within this
     *         log; <code>false</code> otherwise
     */
    public boolean has(ItemId id) {
        if (addedStates.containsKey(id) || modifiedStates.containsKey(id)) {
            return true;
        }
        return false;
    }

    /**
     * Return a node references object given its id. Returns
     * <code>null</code> if the node reference is not in the modified
     * section.
     * @return node references or <code>null</code>
     */
    public NodeReferences get(NodeReferencesId id) {
        return (NodeReferences) modifiedRefs.get(id);
    }

    /**
     * Return an iterator over all added states.
     * @return iterator over all added states.
     */
    public Iterator addedStates() {
        return addedStates.values().iterator();
    }

    /**
     * Return an iterator over all modified states.
     * @return iterator over all modified states.
     */
    public Iterator modifiedStates() {
        return modifiedStates.values().iterator();
    }

    /**
     * Return an iterator over all deleted states.
     * @return iterator over all deleted states.
     */
    public Iterator deletedStates() {
        return deletedStates.values().iterator();
    }

    /**
     * Return an iterator over all modified references.
     * @return iterator over all modified references.
     */
    public Iterator modifiedRefs() {
        return modifiedRefs.values().iterator();
    }

    /**
     * Merge another change log to this change log
     * @param other other change log
     */
    public void merge(ChangeLog other) {
        /*
         * Remove all states from our added set that have
         * now been deleted
         */
        Iterator iter = other.deletedStates.keySet().iterator();
        while (iter.hasNext()) {
            ItemId id = (ItemId) iter.next();
            if (addedStates.remove(id) == null) {
                deletedStates.put(id, other.deletedStates.get(id));
            }
        }

        /* Now merge other changes */
        addedStates.putAll(other.addedStates);
        modifiedStates.putAll(other.modifiedStates);
        modifiedRefs.putAll(other.modifiedRefs);
    }

    /**
     * Push all states contained in the various maps of
     * items we have.
     */
    public void push() {
        Iterator iter = modifiedStates();
        while (iter.hasNext()) {
            ((ItemState) iter.next()).push();
        }
        iter = deletedStates();
        while (iter.hasNext()) {
            ((ItemState) iter.next()).push();
        }
        iter = addedStates();
        while (iter.hasNext()) {
            ((ItemState) iter.next()).push();
        }
    }

    /**
     * After the states have actually been persisted, update their
     * internal states and notify listeners.
     */
    public void persisted() {
        Iterator iter = modifiedStates();
        while (iter.hasNext()) {
            ItemState state = (ItemState) iter.next();
            state.setStatus(ItemState.STATUS_EXISTING);
            state.notifyStateUpdated();
        }
        iter = deletedStates();
        while (iter.hasNext()) {
            ItemState state = (ItemState) iter.next();
            state.setStatus(ItemState.STATUS_EXISTING_REMOVED);
            state.notifyStateDestroyed();
            state.discard();
        }
        iter = addedStates();
        while (iter.hasNext()) {
            ItemState state = (ItemState) iter.next();
            state.setStatus(ItemState.STATUS_EXISTING);
            state.notifyStateCreated();
        }
    }

    /**
     * Reset this change log, removing all members inside the
     * maps we built.
     */
    public void reset() {
        addedStates.clear();
        modifiedStates.clear();
        deletedStates.clear();
        modifiedRefs.clear();
    }

    /**
     * Disconnect all states in the change log from their overlaid
     * states.
     */
    public void disconnect() {
        Iterator iter = modifiedStates();
        while (iter.hasNext()) {
            ((ItemState) iter.next()).disconnect();
        }
        iter = deletedStates();
        while (iter.hasNext()) {
            ((ItemState) iter.next()).disconnect();
        }
        iter = addedStates();
        while (iter.hasNext()) {
            ((ItemState) iter.next()).disconnect();
        }
    }

    /**
     * Undo changes made to items in the change log. Discards
     * added items, refreshes modified and resurrects deleted
     * items.
     * @param parent parent manager that will hold current data
     */
    public void undo(ItemStateManager parent) {
        Iterator iter = modifiedStates();
        while (iter.hasNext()) {
            ItemState state = (ItemState) iter.next();
            try {
                state.connect(parent.getItemState(state.getId()));
                state.pull();
            } catch (ItemStateException e) {
                state.discard();
            }
        }
        iter = deletedStates();
        while (iter.hasNext()) {
            ItemState state = (ItemState) iter.next();
            try {
                state.connect(parent.getItemState(state.getId()));
                state.pull();
            } catch (ItemStateException e) {
                state.discard();
            }
        }
        iter = addedStates();
        while (iter.hasNext()) {
            ((ItemState) iter.next()).discard();
        }
        reset();
    }
}
