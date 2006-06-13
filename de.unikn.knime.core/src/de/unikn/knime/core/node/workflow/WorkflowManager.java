/* 
 * -------------------------------------------------------------------
 * This source code, its documentation and all appendant files
 * are protected by copyright law. All rights reserved.
 * 
 * Copyright, 2003 - 2006
 * Universitaet Konstanz, Germany.
 * Lehrstuhl fuer Angewandte Informatik
 * Prof. Dr. Michael R. Berthold
 * 
 * You may not modify, publish, transmit, transfer or sell, reproduce,
 * create derivative works from, distribute, perform, display, or in
 * any way exploit any of the content, in whole or in part, except as
 * otherwise expressly permitted in writing by the copyright owner.
 * -------------------------------------------------------------------
 * 
 * History
 *   13.02.2005 (M. Berthold): created
 */
package de.unikn.knime.core.node.workflow;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.unikn.knime.core.node.CanceledExecutionException;
import de.unikn.knime.core.node.DefaultNodeProgressMonitor;
import de.unikn.knime.core.node.ExecutionMonitor;
import de.unikn.knime.core.node.InvalidSettingsException;
import de.unikn.knime.core.node.Node;
import de.unikn.knime.core.node.NodeFactory;
import de.unikn.knime.core.node.NodeLogger;
import de.unikn.knime.core.node.NodeSettings;
import de.unikn.knime.core.node.NodeStateListener;
import de.unikn.knime.core.node.NodeStatus;


/**
 * Manager for a workflow holding Nodes and the connecting edge information. The
 * information is stored in a graph based data structure and allows to access
 * predecessors and successors. For performance reasons this implementation is
 * specific to vertices being of type <code>de.unikn.knime.dev.node.Node</code>
 * and (directed) edges connecting ports indicated by indices.
 * 
 * @author M. Berthold, University of Konstanz
 * @author Florian Georg, University of Konstanz
 * @author Thorsten Meinl, University of Konstanz
 */
public class WorkflowManager implements NodeStateListener, WorkflowListener {
    /** Key for connections. */
    public static final String KEY_CONNECTIONS = "connections";

    /** Key for nodes. */
    public static final String KEY_NODES = "nodes";

    /** Key for current running connection id. */
    private static final String KEY_RUNNING_CONN_ID = "runningConnectionID";

    /** Key for current running node id. */
    private static final String KEY_RUNNING_NODE_ID = "runningNodeID";

    /** The node logger for this class. */
    private static final NodeLogger LOGGER = NodeLogger
            .getLogger(WorkflowManager.class);

    // quick access to connections
    private final Map<Integer, ConnectionContainer> m_connectionsByID =
        new HashMap<Integer, ConnectionContainer>();

    // change listener support (transient) <= why? (tm)
    private final transient ArrayList<WorkflowListener> m_eventListeners =
        new ArrayList<WorkflowListener>();

    // quick access to IDs via Nodes
    private final Map<Node, Integer> m_idsByNode =
        new HashMap<Node, Integer>();
    
    
    // quick access to nodes by ID
    private final Map<Integer, NodeContainer> m_nodeContainerByID = 
        new HashMap<Integer, NodeContainer>();
    
    private final WorkflowManager m_parent;
    private final List<WeakReference<WorkflowManager>> m_children =
        new ArrayList<WeakReference<WorkflowManager>>();

    private volatile int m_runningConnectionID = -1;
    
    // internal variables to allow generation of unique indices
    private volatile int m_runningNodeID = -1;
    
    private final Object m_execDone = new Object();
    
    /**
     * Identifier for KNIME workflows. 
     */
    public static final String WORKFLOW_FILE = "workflow.knime";
    
    /**
     * Create new Workflow.
     */
    public WorkflowManager() {
        m_parent = null;
    }
    
    public WorkflowManager(final File file) 
            throws IOException, InvalidSettingsException {
        this(toNodeSettings(file));
        ExecutionMonitor exec = new ExecutionMonitor(
                new DefaultNodeProgressMonitor());
        for (NodeContainer nextNode : m_nodeContainerByID.values()) {
            Node n = nextNode.getNode();
            if (n.isExecuted()) {
                File targetDir = new File(file.getParentFile(), 
                        "node_" + nextNode.getID());
                n.loadInternals(targetDir, exec);
            }
        }

    }
    
    private static NodeSettings toNodeSettings(final File file)
            throws IOException, InvalidSettingsException {
        if (!file.isFile() || !file.getName().equals(WORKFLOW_FILE)) {
            throw new IOException("File must be named: \"" 
                    + WORKFLOW_FILE + "\": " + file);
        }
        return NodeSettings.loadFromXML(new FileInputStream(file));
    }    
    

    /**
     * Creates a new workflow manager and reads a workflow from the given
     * settings object.
     * 
     * @param settings the settings that contain the workflow
     * @throws InvalidSettingsException if an expected key is missing in the
     * settings
     * @deprecated
     */
    @Deprecated
    public WorkflowManager(final NodeSettings settings)
    throws InvalidSettingsException {
        this();
        load(settings);
    }

    /**
     * Creates a new sub workflow manager with the given parent manager.
     * 
     * @param parent the parent workflow manager
     */
    protected WorkflowManager(final WorkflowManager parent) {
        m_parent = parent;
        m_parent.m_children.add(new WeakReference<WorkflowManager>(this));
    }

    /**
     * add a connection between two nodes. The port indices have to be within
     * their valid ranges.
     * 
     * @param idOut identifier of source node
     * @param portOut index of port on source node
     * @param idIn identifier of target node (sink)
     * @param portIn index of port on target
     * @return newly create edge
     * @throws IllegalArgumentException if port indices are invalid
     */
    public ConnectionContainer addConnection(final int idOut,
            final int portOut, final int idIn, final int portIn) {
        NodeContainer nodeOut = m_nodeContainerByID.get(idOut);
        NodeContainer nodeIn = m_nodeContainerByID.get(idIn);

        if (nodeOut == null) {
            throw new IllegalArgumentException("Node with id #" + idOut
                    + " does not exist.");
        }
        if (nodeIn == null) {
            throw new IllegalArgumentException("Node with id #" + idIn
                    + " does not exist.");
        }

        return addConnection(m_nodeContainerByID.get(idOut), portOut,
                m_nodeContainerByID.get(idIn), portIn);
    }

    /**
     * add a connection between two nodes. The port indices have to be within
     * their valid ranges.
     * 
     * @param outNode source node
     * @param portOut index of port on source node
     * @param inNode target node (sink)
     * @param portIn index of port on target
     * @return newly create edge
     */
    public ConnectionContainer addConnection(final NodeContainer outNode,
            final int portOut, final NodeContainer inNode, final int portIn) {
        if (m_parent == null) {
            if (!m_idsByNode.containsKey(outNode.getNode())) {
                throw new IllegalArgumentException("The output node container"
                        + " is not handled by this workflow manager");
            }
            if (!m_idsByNode.containsKey(inNode.getNode())) {
                throw new IllegalArgumentException("The input node container"
                        + " is not handled by this workflow manager");
            }
        }

        ConnectionContainer newConnection = new ConnectionContainer(
                ++m_runningConnectionID, outNode, portOut, inNode, portIn);
        addConnection(newConnection);
        return newConnection;
    }

    
    private void addConnection(final ConnectionContainer cc) {
        if (m_connectionsByID.containsKey(cc.getID())) {
            throw new IllegalArgumentException("A connection with id #" 
                    + cc.getID() + " already exists in the workflow.");
        }
            
        NodeContainer outNode = cc.getSource();
        NodeContainer inNode = cc.getTarget();
        int outPort = cc.getSourcePortID();
        int inPort = cc.getTargetPortID();
        
        m_connectionsByID.put(cc.getID(), cc);
        
        // add outgoing edge
        outNode.addOutgoingConnection(outPort, inNode);

        // add incoming edge
        inNode.addIncomingConnection(inPort, outNode);
        inNode.getNode().getInPort(inPort).connectPort(
                outNode.getNode().getOutPort(outPort));
        
        
        // add this manager as listener for workflow event
        cc.addWorkflowListener(this);        

        // notify listeners
        LOGGER.debug("Added connection (from node id:" + outNode.getID()
                + ", port:" + outPort
                + " to node id:" + inNode.getID() + ", port:" + inPort + ")");
        fireWorkflowEvent(new WorkflowEvent.ConnectionAdded(-1, null, cc));
    }
    
    /**
     * Adds a listener to the workflow, has no effect if the listener is already
     * registered.
     * 
     * @param listener The listener to add
     */
    public void addListener(final WorkflowListener listener) {
        if (!m_eventListeners.contains(listener)) {
            m_eventListeners.add(listener);
        }
    }

    /**
     * Creates a new node from the given factory, adds the node to the workflow
     * and returns the corresponding <code>NodeContainer</code>.
     * 
     * @param factory the factory to create the node
     * @return the <code>NodeContainer</code> representing the created node
     */
    public NodeContainer addNewNode(final NodeFactory factory) {
        Node node = new Node(factory, this);
        LOGGER.debug("adding node '" + node + "' to the workflow...");
        int id = addNode(node);
        LOGGER.debug("done, ID=" + id);
        return getNodeContainer(node);
    }

    /**
     * adds a new node to the workflow.
     * 
     * @param n node to be added
     * @return internal, unique identifier
     */
    private int addNode(final Node n) {
        if (m_idsByNode.containsKey(n)) {
            throw new IllegalArgumentException(
                    "Node already managed by this workflow, "
                            + "can't add multiple times: " + n);
        }
        // create new ID
        final int newNodeID = ++m_runningNodeID;
        assert (!m_nodeContainerByID.containsKey(newNodeID));
        // create new wrapper for this node

        NodeContainer newNode = new NodeContainer(n, newNodeID);        

        // add WorkflowManager as listener for state change events
        newNode.addListener(this);
        // and add it to our hashmap of nodes.
        m_nodeContainerByID.put(newNodeID, newNode);
        m_idsByNode.put(n, newNodeID);

        // notify listeners
        LOGGER.debug("Added " + newNode.getNameWithID());
        fireWorkflowEvent(new WorkflowEvent.NodeAdded(newNodeID, null,
                newNode));

        return newNodeID;
    }

    /**
     * adds a new node to the workflow using the predefined identifier-int. If
     * the identifier is already in use an exception will be thrown.
     * 
     * FG: Do we really need this? Internal id manipulation should not be
     * exposed as public I think. MB: Let's leave it private then for now...
     * 
     * @param nc node to be added
     * @throws IllegalArgumentException when the id already exists
     */
    private void addNodeWithID(final NodeContainer nc) {
        Integer id = new Integer(nc.getID());
        if (m_nodeContainerByID.containsKey(nc.getNode())) {
            throw new IllegalArgumentException("duplicate ID");
        }
        if (m_idsByNode.containsKey(nc.getNode())) {
            throw new IllegalArgumentException("duplicate/illegal node");
        }
        nc.addListener(this);
        m_nodeContainerByID.put(id, nc);
        m_idsByNode.put(nc.getNode(), id);

        // notify listeners
        LOGGER.debug("Added " + nc.getNameWithID());
        fireWorkflowEvent(new WorkflowEvent.NodeAdded(nc.getID(), null, nc));
    }

    /**
     * Returns whether a connection can be added between the given nodes and
     * ports. This may return <code>false</code> if:
     * <ul>
     * <li>Some of the nodeIDs are invalid,</li>
     * <li>some of the port-numbers are invalid,</li>
     * <li>there's already a connection that ends at the given in-port,</li>
     * <li>or (new) this connection would create a loop in the workflow</li>
     * </ul>
     * 
     * @param sourceNode ID of the source node
     * @param outPort Index of the outgoing port
     * @param targetNode ID of the target node
     * @param inPort Index of the incoming port
     * @return <code>true</code> if a connection can be added,
     *         <code>false</code> otherwise
     */
    public boolean canAddConnection(final int sourceNode, final int outPort,
            final int targetNode, final int inPort) {
        if ((sourceNode < 0) || (outPort < 0) || (targetNode < 0)
                || (inPort < 0)) {
            // easy sanity check failed - return false;
            return false;
        }

        Node src = getNode(sourceNode);
        Node targ = getNode(targetNode);

        boolean nodesValid = (src != null) && (targ != null);
        if (!nodesValid) {
            // Nodes don't exist (whyever) - return failure
            LOGGER.error("WFM: checking for connection between non existing"
                    + " nodes!");
            return false;
        }

        boolean portNumsValid = (src.getNrOutPorts() > outPort)
                && (targ.getNrInPorts() > inPort) && (outPort >= 0)
                && (inPort >= 0);
        if (!portNumsValid) {
            // port numbers don't exist - return failure
            LOGGER.error("WFM: checking for connection for non existing"
                    + " ports!");
            return false;
        }

        ConnectionContainer conn = getIncomingConnectionAt(
                getNodeContainer(targ), inPort);
        boolean hasConnection = (conn != null);
        if (hasConnection) {
            // input port already has a connection - return failure
            return false;
        }

        boolean isDataConn = targ.isDataInPort(inPort)
                && src.isDataOutPort(outPort);
        boolean isModelConn = !targ.isDataInPort(inPort)
                && !src.isDataOutPort(outPort);
        if (!isDataConn && !isModelConn) {
            // trying to connect data to model port - return failure
            return false;
        }

        // the easy tests all succeeded, now check if we are creating a loop
        NodeContainer targC = getNodeContainer(targ);
        assert targC.getID() == targetNode;
        NodeContainer srcC = getNodeContainer(src);
        assert srcC.getID() == sourceNode;
        boolean loop = targC.isFollowedBy(srcC);
        // if (loop) {
        // LOGGER.warn("Attempt to create loop (from node id:" + srcC.getID()
        // + ", port:" + inPort + " to node id:" + targC.getID()
        // + ", port:" + outPort + ")");
        // }
        return !loop;
    }
    
    
    /**
     * Cancel execution of all remaining nodes after the specified node.
     * 
     * @param nodeID the node's ID after which excution is to be canceled.
     */
    public synchronized void cancelExecutionAfterNode(final int nodeID) {
        NodeContainer thisNodeC = m_nodeContainerByID.get(nodeID);
        cancelExecutionAfterNode(thisNodeC);
        // check if any other nodes are either in the queue or already
        // executing (= not idle)
        int activeNodes = 0;
        for (NodeContainer nodeC : m_nodeContainerByID.values()) {
            if (nodeC.getState() != NodeContainer.State.Idle) {
                activeNodes++;
            }
        }
        if (activeNodes == 0) {
            // all nodes are idle, fire event that workflow pool is empty
            fireWorkflowEvent(new WorkflowEvent.ExecPoolDone(-1, null, null));
        }
    }

    private synchronized void cancelExecutionAfterNode(final NodeContainer n) {
        // try to cancel this node
        if ((n.getState() == NodeContainer.State.WaitingToBeExecutable)) {
            // ok, we can simply change the node's flag
            n.setState(NodeContainer.State.Idle);
        }
        if ((n.getState() == NodeContainer.State.IsExecutable)) {
            // ok, we can simply change the node's flag
            n.setState(NodeContainer.State.Idle);
        }
        if ((n.getState() == NodeContainer.State.WaitingForExecution)) {
            // more complicated, we need to notify the node's progress monitor
            // that we would like to cancel execution
            n.cancelExecution();
        }
        if ((n.getState() == NodeContainer.State.CurrentlyExecuting)) {
            // more complicated, we need to notify the node's progress monitor
            // that we would like to cancel execution
            n.cancelExecution();
        }
        // and also try to cancel all successors
        NodeContainer[][] nodes = n.getSuccessors();
        for (int i = 0; i < nodes.length; i++) {
            NodeContainer[] portNodes = nodes[i];
            for (int j = 0; j < portNodes.length; j++) {
                cancelExecutionAfterNode(portNodes[j]);
            }
        }
    }

    // ////////////////////////
    // Routines for Execution
    // ////////////////////////

    /**
     * Cancel execution of all remaining nodes. Note that this is not a
     * guarantee that all remaining nodes RIGHT NOW will be canceled but all of
     * them will be asked to terminate. This routine requires the goodwill of
     * the implementations of the individual execute-routines.
     */
    public synchronized void cancelExecutionAllRemainingNodes() {
        int canceledNodes = 0; // how many could we cancel
        int currentlyInQueue = 0; // how many are already in the queue?
        int currentlyExecuting = 0; // how many are already executing?
        for (NodeContainer nc : m_nodeContainerByID.values()) {
            if (nc.getState() == NodeContainer.State.IsExecutable) {
                // node has not yet been started, simlpy change back to IDLE
                nc.setState(NodeContainer.State.Idle);
                canceledNodes++;
            }
            if (nc.getState() == NodeContainer.State.WaitingToBeExecutable) {
                // node has not yet been started, simlpy change back to IDLE
                nc.setState(NodeContainer.State.Idle);
                canceledNodes++;
            }
            if (nc.getState() == NodeContainer.State.WaitingForExecution) {
                // these ones we need to cancel, since they have already
                // been returned a requester as "executable"
                nc.cancelExecution();
                currentlyInQueue++;
            }
            if (nc.getState() == NodeContainer.State.CurrentlyExecuting) {
                // these nodes are currently being executed, try to cancel.
                nc.cancelExecution();
                currentlyExecuting++;
            }
        }
        if ((currentlyExecuting == 0) && (currentlyInQueue == 0)) {
            // done. Otherwise we'll be done once the remaining ones return
            // however we made sure no new nodes are going to be executed.
            fireWorkflowEvent(new WorkflowEvent.ExecPoolDone(-1, null, null));
        }
    }


    /**
     * Creates and returns a new workflowmanager that handles a workflow
     * that is contained in the workflow that this manager handles.
     * 
     * @return a subordinate workflow manager
     */
    public WorkflowManager createSubManager() {
        return new WorkflowManager(this);
    }

    /**
     * Creates additional nodes and optional connections between those specified
     * in the settings object.
     * 
     * @param settings the <code>NodeSettings</code> object describing the sub
     *            workflow to add to this workflow manager
     * @param positionChangeMultiplier factor to determine the change of the
     *            position of a copied object
     * 
     * @return the ids of the newly created containers
     * 
     * @throws InvalidSettingsException thrown if the passed settings are not
     *             valid
     */
    public int[] createSubWorkflow(final NodeSettings settings,
            final int positionChangeMultiplier)
            throws InvalidSettingsException {
        NodeSettings nodes = settings.getConfig(KEY_NODES); // Node-Subconfig

        // the new ids to return
        ArrayList<Integer> newIDs = new ArrayList<Integer>();

        // the map is used to map the old node id to the new one
        Map<Integer, Integer> idMap = new HashMap<Integer, Integer>();

        for (String nodeKey : nodes.keySet()) {
            NodeSettings nodeSetting = null;
            try {
                // retrieve config object for each node
                nodeSetting = nodes.getConfig(nodeKey);
                // create NodeContainer based on NodeSettings object
                final NodeContainer newNode =
                    new NodeContainer(nodeSetting, this);


                // change the id, as this id is already in use (it was copied)
                // first remeber the old id "map(oldId, newId)"

                // remember temporarily the old id
                final int oldId = newNode.getID();

                // create new id
                final int newId = ++m_runningNodeID;
                idMap.put(newNode.getID(), newId);
                newNode.changeId(newId);
                // remember the new id for the return value
                newIDs.add(newId);

                // finaly change the extra info so that the copies are
                // located differently (if not null)
                NodeExtraInfo extraInfo = newNode.getExtraInfo();
                if (extraInfo != null) {
                    extraInfo.changePosition(40 * positionChangeMultiplier);
                }

                // set the user name to the new id if the init name
                // was set before e.g. "Node_44"
                // get set username
                String currentUserNodeName = newNode.getCustomName();

                // create temprarily the init user name of the copied node
                // to check wether the current name was changed
                String oldInitName = "Node " + (oldId + 1);
                if (oldInitName.equals(currentUserNodeName)) {
                    newNode.setCustomName("Node " + (newId + 1));
                }

                // and add it to workflow
                addNodeWithID(newNode);
            } catch (InvalidSettingsException ise) {
                LOGGER.warn("Could not create node " + nodeKey + " reason: "
                        + ise.getMessage());
                LOGGER.debug(nodeSetting, ise);
            }
        }
        // read connections
        NodeSettings connections = settings.getConfig(KEY_CONNECTIONS);
        for (String connectionKey : connections.keySet()) {
            // retrieve config object for next connection
            NodeSettings connectionConfig = connections
                    .getConfig(connectionKey);
            // and add appropriate connection to workflow
            try {
                // get the new id from the map
                // read ids and port indices
                int oldSourceID = ConnectionContainer
                        .getSourceIdFromConfig(connectionConfig);
                int oldTargetID = ConnectionContainer
                        .getTargetIdFromConfig(connectionConfig);

                // check if both (source and target node have been selected
                // if not, the connection is omitted
                if (idMap.get(oldSourceID) == null
                        || idMap.get(oldTargetID) == null) {
                    continue;
                }
                
                ConnectionContainer cc = new ConnectionContainer(
                        ++m_runningConnectionID, connectionConfig, this, idMap);
                addConnection(cc);
                // add the id to the new ids
                newIDs.add(cc.getID());
            } catch (InvalidSettingsException ise) {
                LOGGER.warn("Could not create connection " + connectionKey
                        + " reason: " + ise.getMessage());
                LOGGER.debug(connectionConfig, ise);
            }
        }

        int[] idArray = new int[newIDs.size()];
        int i = 0;
        for (Integer newId : newIDs) {
            idArray[i++] = newId;
        }

        return idArray;
    }

    /**
     * Removes all connections (incoming and outgoing) from a node container.
     * Note that this results in a bunch of workflow events !
     * 
     * @param nodeCont The container which should be completely disconnected
     */
    public void disconnectNodeContainer(final NodeContainer nodeCont) {
        int numIn = nodeCont.getNode().getNrInPorts();
        int numOut = nodeCont.getNode().getNrOutPorts();
        
        List<ConnectionContainer> connections =
            new ArrayList<ConnectionContainer>();
        // collect incoming connections
        for (int i = 0; i < numIn; i++) {
            ConnectionContainer c = getIncomingConnectionAt(nodeCont, i);
            if (c != null) {
                connections.add(c);
            }
        }
        // collect outgoing connections
        for (int i = 0; i < numOut; i++) {
            List<ConnectionContainer> cArr =
                getOutgoingConnectionsAt(nodeCont, i);
            if (cArr != null) {
                connections.addAll(cArr);
            }
        }

        // remove all collected connections
        for (ConnectionContainer container : connections) {
            removeConnectionIfExists(container);
        }
    }

    /*
     * Notifes all registered listeners of the event. 
     */
    @SuppressWarnings("unchecked")
    private void fireWorkflowEvent(final WorkflowEvent event) {
        // we make a copy here because a listener can add or remove
        // itself or another listener during handling the event
        // this will then cause a ConcurrentModificationException
        ArrayList<WorkflowListener> temp =
            (ArrayList<WorkflowListener>)m_eventListeners.clone();
        for (WorkflowListener l : temp) {
            l.workflowChanged(event);
        }
        
        if (event instanceof WorkflowEvent.ExecPoolDone) {
            synchronized (m_execDone) {
                m_execDone.notifyAll();
            }
        }
    }

    /**
     * Returns the incoming connection that exist at some in-port on some node.
     * 
     * @param container a node in the workflow
     * @param portNum index of the in-port
     * @return the connection that is attached to the given in-port or
     * <code>null</code> if no such connection exists
     * @throws IllegalArgumentException If either nodeID or portNum is invalid.
     */
    public ConnectionContainer getIncomingConnectionAt(
            final NodeContainer container, final int portNum) {
        if (container == null) {
            throw new NullPointerException("container must not be null");
        }
        // Find all outgoing connections for the given node
        for (ConnectionContainer conn : m_connectionsByID.values()) {
            // check if this connection affects the right node and port
            // if so, return the connection
            if ((conn.getTarget().equals(container))
                    && (conn.getTargetPortID() == portNum)) {
                return conn;
            }
        }

        return null;
    }

    /**
     * Return next available Node which needs to be executed. In theory at some
     * point in time this may incorporate some clever sorting mechanism, right
     * now it returns runnable nodes in some rather arbitrary, non-deterministic
     * order. Note that a return value of null only means that right now there
     * is no runnable node - there may be one later on when another node sends
     * an event indicating that it is done executing. The final end of execution
     * is indicated by the appropriate workflow event.
     * 
     * TODO: right now an executable node is only returned once even if it is
     * never actually executed! We need a way to have a watchdog timer that
     * resets these flags if nothing has happened for "too long"...
     * 
     * @return next runnable node or <code>null</code> of none is currently (!)
     * available.
     */
    public synchronized NodeContainer getNextExecutableNode() {
        // right now just look for next runnable node from start every time
        for (NodeContainer nc : m_nodeContainerByID.values()) {
            if ((nc.getState() == NodeContainer.State.IsExecutable)
                    && (nc.getNode().isExecutable())) {
                nc.setState(NodeContainer.State.WaitingForExecution);
                LOGGER.debug("returning node id=" + nc.getID()
                        + " as next available executable.");
                return nc;
            }
        }
        
        if (m_parent != null) {
            return m_parent.getNextExecutableNode();
        }
        // didn't find any runnable node: return null.
        return null;
    }

    
    /**
     * Starts the execution of a workflow (or parts of it) by sending events.
     * No node will be directly executed by this method.
     * 
     * @param wait <code>true</code> if the caller should be blocked until the
     * execution of the workflow has finished, <code>false</code> if the call
     * should return immediately
     */
    public void startExecution(final boolean wait) {
        checkForExecutableNodes();
        if (wait) {
            synchronized (m_execDone) {
                try {
                    m_execDone.wait();
                } catch (InterruptedException ex) {
                    // may happen, so what?
                }
            }
        }
    }
    
    /**
     * return a node for a specific identifier. Returns null if ID is not found,
     * throws an exception if something odd happens.
     * 
     * @param id the identifier of the node request
     * @return node matching the given ID
     */
    private Node getNode(final int id) {
        NodeContainer nodeObj = m_nodeContainerByID.get(id);
        if (nodeObj == null) {
            return null;
        }
        return nodeObj.getNode();
    }

    /**
     * Returns the node container that is handled by the manager for the given
     * node.
     * 
     * @param node The node
     * @return The container that wraps the node
     */
    public NodeContainer getNodeContainer(final Node node) {
        Integer id = m_idsByNode.get(node);
        if (id == null) {
            if (m_parent != null) {
                return m_parent.getNodeContainer(node);
            } else {
                return null;
            }
        }
        
        NodeContainer cont = m_nodeContainerByID.get(id);
        return cont;
    }

    /**
     * Returns the node container that is handled by the manager for the given
     * id.
     * 
     * @param id The id of the <code>Node</code> whose
     *            <code>NodeContainer</code> should be returned
     * @return The container that wraps the node of the given id
     */
    public NodeContainer getNodeContainerById(final int id) {
        NodeContainer cont = m_nodeContainerByID.get(new Integer(id));
        return cont;
    }

    /**
     * Returns all nodes currently managed by this instance.
     * 
     * @return All the managed node containers.
     */
    public Collection<NodeContainer> getNodes() {
        return Collections.unmodifiableCollection(m_nodeContainerByID.values());
    }

    /**
     * Returns the outgoing connections that exist at some out-port on some
     * node.
     * 
     * @param container The container in the workflow.
     * @param portNum Index of the out-port
     * @return Array containing the connection container objects that are
     *         associated to the given out-port on the node
     * @throws IllegalArgumentException If either nodeID or portNum is invalid.
     */
    public List<ConnectionContainer> getOutgoingConnectionsAt(
            final NodeContainer container, final int portNum) {
        List<ConnectionContainer> foundConnections =
            new ArrayList<ConnectionContainer>();

        // If the node is contained, process it
        if (container != null) {
            // Find all outgoing connections for the given node
            for (ConnectionContainer conn : m_connectionsByID.values()) {
                // check if this connection affects the right node and port
                if ((conn.getSource().equals(container))
                        && (conn.getSourcePortID() == portNum)) {
                    foundConnections.add(conn);
                }
            }
        } else {
            throw new IllegalArgumentException(
                    "The node is not contained in the workflow");
        }

        return foundConnections;
    }

    /**
     * Read workflow setup from configuration object. Nodes will be read first
     * and then used to reconstruct the connections between them since the nodes
     * do not store their connections.
     * 
     * @param settings the configuration object
     * @throws InvalidSettingsException when a key is missing
     */
    public void load(final NodeSettings settings)
            throws InvalidSettingsException {
        // read name
        // read running ids for new nodes and connections
        m_runningNodeID = settings.getInt(KEY_RUNNING_NODE_ID);
        m_runningConnectionID = settings.getInt(KEY_RUNNING_CONN_ID);
        
        // read nodes
        NodeSettings nodes = settings.getConfig(KEY_NODES); // Node-Subconfig
        // object
        // get all keys in there
        for (String nodeKey : nodes.keySet()) {
            NodeSettings nodeSetting = null;
            try {
                // retrieve config object for each node
                nodeSetting = nodes.getConfig(nodeKey);
                // create NodeContainer based on NodeSettings object
                NodeContainer newNode = new NodeContainer(nodeSetting, this);
                // and add it to workflow
                addNodeWithID(newNode);
            } catch (InvalidSettingsException ise) {
                LOGGER.warn("Could not create node " + nodeKey + " reason: "
                        + ise.getMessage());
                LOGGER.debug(nodeSetting, ise);
            }
        }
        
        // read connections
        NodeSettings connections = settings.getConfig(KEY_CONNECTIONS);
        for (String connectionKey : connections.keySet()) {
            // retrieve config object for next connection
            NodeSettings connectionConfig = connections
                    .getConfig(connectionKey);
            // and add appropriate connection to workflow
            try {
                ConnectionContainer cc = new ConnectionContainer(
                        ++m_runningConnectionID, connectionConfig, this);
                addConnection(cc);
            } catch (InvalidSettingsException ise) {
                LOGGER.warn("Could not create connection " + connectionKey
                        + " reason: " + ise.getMessage());
                LOGGER.debug(connectionConfig, ise);
            }
        }
    }
    
    
    /**
     * Mark all nodes that are not yet executed as "to be executed" and the ones
     * that are actually executable (all predecessors data is available) as
     * "runnable". If no executable nodes have been found, a
     * {@link WorkflowEvent.ExecPoolDone} is fired.
     */
    public synchronized void prepareForExecAllNodes() {
        for (NodeContainer nc : m_nodeContainerByID.values()) {
            if (!(nc.getNode().isExecuted())
                && (nc.getState() != NodeContainer.State.WaitingForExecution)
                && (nc.getState() != NodeContainer.State.CurrentlyExecuting)) {
                
                if (nc.getNode().isExecutable()) {
                    nc.setState(NodeContainer.State.IsExecutable);
                } else {
                    nc.setState(NodeContainer.State.WaitingToBeExecutable);
                }
            }
        }
        
        // FIXME do not execute all nodes in the parent flow
        if (m_parent != null) { m_parent.prepareForExecAllNodes(); }
    }

    /**
     * Mark all nodes that are neceesary for the specificed node to be
     * excecuted as "to be executed" - the remaining behaviour is similar to
     * {@link #prepareForExecAllNodes()}.
     * 
     * @param nodeID the node's ID which is ultimately to be executed.
     */
    public synchronized void prepareForExecUpToNode(final int nodeID) {
        NodeContainer nc = m_nodeContainerByID.get(nodeID);
        prepareForExecUpToNode(nc);
    }

    /**
     * Private routine to mark this NodeContainer and all predecessors requiring
     * execution as EXECUTABLE or WAITING_TO_BE_EXECUTABLE. Calls itself
     * recursively until all preceeding nodes are either executed or their state
     * is set appropriately.
     * 
     * @param n a node container
     */
    private synchronized void prepareForExecUpToNode(final NodeContainer n) {
        if (!(n.getNode().isExecuted())
                && (n.getState() != NodeContainer.State.WaitingForExecution)
                && (n.getState() != NodeContainer.State.CurrentlyExecuting)) {
            // node is not already executed (or waiting to be) - set flag
            // according to the underlying Node's "isExecutable" status.
            if (n.getNode().isExecutable()) {
                n.setState(NodeContainer.State.IsExecutable);
            } else {
                n.setState(NodeContainer.State.WaitingToBeExecutable);
            }

            // process all predecessors (only if this node was not executed!)
            for (NodeContainer pred : n.getPredecessors()) {
                if (pred != null) {
                    if (m_idsByNode.containsKey(pred.getNode())) {
                        prepareForExecUpToNode(pred);
                    } else if (m_parent != null) {
                        m_parent.prepareForExecUpToNode(pred);
                    } else {
                        throw new IllegalStateException("The node #"
                                + pred.getID() + "(" + pred.nodeToString() + ")"
                                + " is not part of this workflow manager or"
                                + " its parent manager");
                    }
                } else {
                    LOGGER.error(n.getNameWithID()
                            + " is not executable: check connections");
                }
            }
        }
    }

    /**
     * Deletes a connection between two nodes.
     * 
     * @param connection to be deleted
     */
    public synchronized void removeConnectionIfExists(
            final ConnectionContainer connection) {
        // if connection does not exist simply return
        if (!(m_connectionsByID.containsKey(connection.getID()))) {
            return;
        }

        // retrieve source and target node
        NodeContainer nodeOut = connection.getSource();
        int portOut = connection.getSourcePortID();
        NodeContainer nodeIn = connection.getTarget();
        int portIn = connection.getTargetPortID();
        // remove outgoing edge
        nodeOut.removeOutgoingConnection(portOut, nodeIn);
        // remove incoming edge
        nodeIn.removeIncomingConnection(portIn);
        // also disconnect the two underlying Nodes.
        nodeIn.getNode().getInPort(portIn).disconnectPort();
        // finally remove connection from internal list
        m_connectionsByID.remove(connection.getID());

        // notify listeners
        LOGGER.info("Removed connection (from node id:" + nodeOut.getID()
                + ", port:" + portOut + " to node id:" + nodeIn.getID()
                + ", port:" + portIn + ")");
        fireWorkflowEvent(new WorkflowEvent.ConnectionRemoved(-1, connection,
                null));
    }

    /**
     * Removes a listener from the worklflow, has no effekt if the listener was
     * not registered before.
     * 
     * @param listener The listener to remove
     */
    public void removeListener(final WorkflowListener listener) {
        m_eventListeners.remove(listener);
    }

    /**
     * Removes a node from the workflow. If the node still has connections they
     * are removed.
     * 
     * @param container node to be removed
     */
    public synchronized void removeNode(final NodeContainer container) {
        Node node = container.getNode();
        Integer id = m_idsByNode.get(node);
        // tell node that it has been disconnected (close views...)
        node.detach();

        if (id != null) {
            // FG: First remove all connections
            disconnectNodeContainer(container);

            container.removeAllListeners();

            m_nodeContainerByID.remove(id);
            m_idsByNode.remove(container.getNode());

            // notify listeners
            LOGGER.debug("Removed: " + container.getNameWithID());
            fireWorkflowEvent(new WorkflowEvent.NodeRemoved(id, container,
                    null));
        } else {
            LOGGER.error("Could not find (and remove): " + node.getName());
            throw new IllegalArgumentException(
                    "Node not managed by this workflow: " + node);
        }
    }

    
    /**
     * Removes all nodes and connection from the workflow.
     */
    public synchronized void clear() {
        List<NodeContainer> containers =
            new ArrayList<NodeContainer>(m_nodeContainerByID.values());
        for (NodeContainer nc : containers) {
            removeNode(nc);
        }
        
        assert (m_nodeContainerByID.size() == 0);
        assert (m_connectionsByID.size() == 0);
        assert (m_idsByNode.size() == 0);
        m_runningConnectionID = -1;
        m_runningNodeID = -1;
    }
    
    /**
     * Stores all workflow information into the given configuration. Note that
     * we have to store both nodes and connections and re-create them in that
     * order since connections reference IDs of nodes. Nodes themselves will not
     * store their predecessors or successors.
     * 
     * @param settings the configuration the current settings are written to.
     * @see #load
     * @deprecated
     */
    @Deprecated
    public synchronized void save(final NodeSettings settings) {

        // save name
        // save current running ids
        settings.addInt(KEY_RUNNING_NODE_ID, m_runningNodeID);
        settings.addInt(KEY_RUNNING_CONN_ID, m_runningConnectionID);
        // save nodes in an own sub-config object as a series of configs
        NodeSettings nodes = settings.addConfig(KEY_NODES);
        for (NodeContainer nextNode : m_nodeContainerByID.values()) {
            // and save it to it's own config object
            NodeSettings nextNodeConfig = nodes.addConfig("node_"
                    + nextNode.getID());
            nextNode.save(nextNodeConfig);
            // TODO notify about node settings saved ????
        }
        // save connections in an own sub-config object as a series of configs
        NodeSettings connections = settings.addConfig(KEY_CONNECTIONS);
        for (ConnectionContainer nextConnection : m_connectionsByID.values()) {
            // and save it to it's own config object
            NodeSettings nextConnectionConfig = connections
                    .addConfig("connection_" + nextConnection.getID());
            nextConnection.save(nextConnectionConfig);
            // TODO notify about connection settings saved ????
        }
    }
    
    public void save(final File file) 
        throws IOException, CanceledExecutionException {
        if (!file.isFile() || !file.getName().equals(WORKFLOW_FILE)) {
            throw new IOException("File must be named: \"" 
                    + WORKFLOW_FILE + "\": " + file);
        }
        // file.getName is ignored when reading.
        NodeSettings settings = new NodeSettings(file.getName());
        this.save(settings);
        FileOutputStream fos = new FileOutputStream(file);
        settings.saveToXML(fos);
        ExecutionMonitor exec = new ExecutionMonitor(
                new DefaultNodeProgressMonitor());
        for (NodeContainer nextNode : m_nodeContainerByID.values()) {
            Node n = nextNode.getNode();
            if (n.isExecuted()) {
                File targetDir = new File(file.getParentFile(), 
                        "node_" + nextNode.getID());
                if (!targetDir.isDirectory() && !targetDir.mkdir()) {
                    throw new IOException("Unable to create dir: " + targetDir);
                }
                n.saveInternals(targetDir, exec);
            }
        }
    }

    
    /**
     * Saves the workflow but omits all nodes and their connection in the
     * given set.
     * 
     * @param settings the configuration the current settings are written to.
     * @param omitNodes a set of nodes that should not be saved
     * @see #save(NodeSettings)
     */
    public synchronized void save(final NodeSettings settings,
            final Set<NodeContainer> omitNodes) {
        List<Map.Entry<Integer, NodeContainer>> tempNodes =
            new ArrayList<Map.Entry<Integer, NodeContainer>>();
        
        for (Iterator<Map.Entry<Integer, NodeContainer>> it =
            m_nodeContainerByID.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, NodeContainer> e = it.next();
            if (omitNodes.contains(e.getValue())) {
                tempNodes.add(e);
                it.remove();
            }            
        }
        
        List<Map.Entry<Integer, ConnectionContainer>> tempConns =
            new ArrayList<Map.Entry<Integer, ConnectionContainer>>();
        
        for (Iterator<Map.Entry<Integer, ConnectionContainer>> it =
            m_connectionsByID.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, ConnectionContainer> e = it.next();
            if (omitNodes.contains(e.getValue().getSource()) 
                    || omitNodes.contains(e.getValue().getTarget())) {
                tempConns.add(e);
                it.remove();
            }            
        }
        
        save(settings);
        
        for (Map.Entry<Integer, NodeContainer> e : tempNodes) {
            m_nodeContainerByID.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<Integer, ConnectionContainer> e : tempConns) {
            m_connectionsByID.put(e.getKey(), e.getValue());
        }
    }
    
    
    /**
     * Callback for NodeContainer state-changed events. Update pool of
     * executable nodes if we are in "workflow execution" mode.
     * 
     * @param state The node state event type.
     * @param nodeID The node's ID.
     */
    public synchronized void stateChanged(final NodeStatus state,
            final int nodeID) {
        NodeContainer changedNode = m_nodeContainerByID.get(nodeID);
        assert (changedNode != null);        
        
        
        // if this is an event indicating the start of a node's execution:
        if (state instanceof NodeStatus.StartExecute) {
            // change state from WAITING_FOR_EXECUTION to CURRENTLY_EXECUTING
            assert (changedNode.getState()
                    == NodeContainer.State.WaitingForExecution);
            changedNode.setState(NodeContainer.State.CurrentlyExecuting);
        } else if (state instanceof NodeStatus.EndExecute) {
            // if this is an event indicating the end of a node's execution:
            // change state from CURRENTLY_EXECUTING to IDLE
            // assert (changedNode.getState() ==
            // NodeContainer.STATE_CURRENTLY_EXECUTING);
            changedNode.setState(NodeContainer.State.Idle);
        
            checkForExecutableNodes();
        } else if (state instanceof NodeStatus.Reset) {
            fireWorkflowEvent(new WorkflowEvent.NodeReset(nodeID, null, null));
        } else if (state instanceof NodeStatus.Configured) {
            fireWorkflowEvent(new WorkflowEvent.NodeConfigured(nodeID, null,
                    null));
        } else if (state instanceof NodeStatus.ExtrainfoChanged) {
            fireWorkflowEvent(new WorkflowEvent.NodeExtrainfoChanged(nodeID,
                    null, null));
        }        
    }
    
    
    private void checkForExecutableNodes() {
        // check if there are any new nodes that need to be run:
        int newExecutables = 0;
        for (NodeContainer nc : m_nodeContainerByID.values()) {
            if ((nc.getState() == NodeContainer.State.WaitingToBeExecutable)
                    && (nc.getNode().isExecutable())) {
                nc.setState(NodeContainer.State.IsExecutable);
                newExecutables++;
            } else if ((nc.isAutoExecutable())
                    && (nc.getState() == NodeContainer.State.Idle)
                    && (nc.getNode().isExecutable())) {
                nc.setState(NodeContainer.State.IsExecutable);
                newExecutables++;
            } else if (nc.getState() == NodeContainer.State.IsExecutable) {
                newExecutables++;
            }
        }
        
        
        if (newExecutables > 0) {
            fireWorkflowEvent(new WorkflowEvent.ExecPoolChanged(-1, null,
                    null));
        }


        int runningNodes = 0;
        // if not, check if there are some remaining that need to be run:
        for (NodeContainer nc : m_nodeContainerByID.values()) {
            if ((nc.getState() == NodeContainer.State.CurrentlyExecuting)
            || (nc.getState() == NodeContainer.State.WaitingForExecution)) {
                runningNodes++;
            }
        }
        
        if ((newExecutables == 0) && (runningNodes == 0)) {
            // did not find any remaining nodes with an active action code: done
            fireWorkflowEvent(new WorkflowEvent.ExecPoolDone(-1, null, null));
            LOGGER.info("Workflow Pool done.");
            // reset all flags to IDLE (just in case we missed some)
            for (NodeContainer nc : m_nodeContainerByID.values()) {
                nc.setState(NodeContainer.State.Idle);
            }
        }

        
        // check all child managers if they have some nodes to execute now
        Iterator<WeakReference<WorkflowManager>> it = m_children.iterator();
        while (it.hasNext()) {
            WeakReference<WorkflowManager> wr = it.next();
            if (wr.get() == null) {
                it.remove();
            } else {
                wr.get().checkForExecutableNodes();
            }
        }
    }
    
    /**
     * Callback for Workflow events.
     * 
     * @param event The thrown event
     */
    public void workflowChanged(final WorkflowEvent event) {
        if (event instanceof WorkflowEvent.ConnectionExtrainfoChanged) {
            // just forward the event
            fireWorkflowEvent(event);
        }
    }
}
