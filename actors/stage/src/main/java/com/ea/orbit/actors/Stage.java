/*
Copyright (C) 2015 Electronic Arts Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1.  Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
2.  Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
    its contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.ea.orbit.actors;

import com.ea.orbit.actors.cluster.ClusterPeer;
import com.ea.orbit.actors.cluster.JGroupsClusterPeer;
import com.ea.orbit.actors.cluster.NodeAddress;
import com.ea.orbit.actors.extensions.ActorExtension;
import com.ea.orbit.actors.extensions.LifetimeExtension;
import com.ea.orbit.actors.runtime.*;
import com.ea.orbit.actors.runtime.Runtime;
import com.ea.orbit.actors.runtime.cloner.ExecutionObjectCloner;
import com.ea.orbit.actors.runtime.cloner.KryoCloner;
import com.ea.orbit.annotation.Config;
import com.ea.orbit.annotation.Wired;
import com.ea.orbit.concurrent.ExecutorUtils;
import com.ea.orbit.concurrent.Task;
import com.ea.orbit.container.Container;
import com.ea.orbit.container.Startable;
import com.ea.orbit.metrics.annotations.ExportMetric;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Singleton
public class Stage implements Startable
{
    private static final Logger logger = LoggerFactory.getLogger(Stage.class);

    private static final int DEFAULT_EXECUTION_POOL_SIZE = 128;

    @Config("orbit.actors.clusterName")
    private String clusterName;

    @Config("orbit.actors.nodeName")
    private String nodeName;

    @Config("orbit.actors.stageMode")
    private StageMode mode = StageMode.HOST;

    @Config("orbit.actors.executionPoolSize")
    private int executionPoolSize = DEFAULT_EXECUTION_POOL_SIZE;

    @Config("orbit.actors.extensions")
    private List<ActorExtension> extensions = new ArrayList<>();

    @Config("orbit.actors.stickyHeaders")
    private Set<String> stickyHeaders = new HashSet<>();

    @Wired
    private Container container;

    public enum StageMode
    {
        FRONT_END, // no activations
        HOST // allows activations
    }

    private ClusterPeer clusterPeer;
    @Wired
    private Messaging messaging;
    @Wired
    private Execution execution;
    @Wired
    private Hosting hosting;
    private boolean startCalled;
    private Clock clock;
    private ExecutorService executionPool;
    private ExecutorService messagingPool;
    private ExecutionObjectCloner objectCloner;

    static
    {
        try
        {
            Class.forName("com.ea.orbit.async.Async");
            try
            {
                // async is present in the classpath, let's make sure await is initialized
                Class.forName("com.ea.orbit.async.Await").getMethod("init").invoke(null);
            }
            catch(Exception ex)
            {
                // this might be a problem, logging.
                logger.error("Error initializing orbit-async", ex);
            }

        }
        catch (Exception ex)
        {
            // no problem, application doesn't use orbit async.
        }
    }

    public static class Builder {

        private Clock clock;
        private ExecutorService executionPool;
        private ExecutorService messagingPool;
        private ExecutionObjectCloner objectCloner;
        private ClusterPeer clusterPeer;

        private String clusterName;
        private String nodeName;
        private StageMode mode = StageMode.HOST;
        private int executionPoolSize = DEFAULT_EXECUTION_POOL_SIZE;

        private Messaging messaging;

        private List<ActorExtension> extensions = new ArrayList<>();
        private Set<String> stickyHeaders = new HashSet<>();

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder executionPool(ExecutorService executionPool) {
            this.executionPool = executionPool;
            return this;
        }

        public Builder messagingPool(ExecutorService messagingPool) {
            this.messagingPool = messagingPool;
            return this;
        }

        public Builder clusterPeer(ClusterPeer clusterPeer) {
            this.clusterPeer = clusterPeer;
            return this;
        }

        public Builder objectCloner(ExecutionObjectCloner objectCloner) {
            this.objectCloner = objectCloner;
            return this;
        }

        public Builder clusterName(String clusterName) {
            this.clusterName = clusterName;
            return this;
        }

        public Builder nodeName(String nodeName) {
            this.nodeName = nodeName;
            return this;
        }

        public Builder mode(StageMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder messaging(Messaging messaging) {
            this.messaging = messaging;
            return this;
        }

        public Builder extensions(ActorExtension ...extensions)
        {
            Collections.addAll(this.extensions, extensions);
            return this;
        }

        public Builder stickyHeaders(String ...stickyHeaders)
        {
            Collections.addAll(this.stickyHeaders, stickyHeaders);
            return this;
        }

        public Stage build() {
            Stage stage = new Stage();
            stage.setClock(clock);
            stage.setExecutionPool(executionPool);
            stage.setMessagingPool(messagingPool);
            stage.setObjectCloner(objectCloner);
            stage.setClusterName(clusterName);
            stage.setClusterPeer(clusterPeer);
            stage.setNodeName(nodeName);
            stage.setMode(mode);
            stage.setExecutionPoolSize(executionPoolSize);
            extensions.forEach(stage::addExtension);
            stage.setMessaging(messaging);
            stage.addStickyHeaders(stickyHeaders);
            return stage;
        }

    }

    public void addStickyHeaders(Collection<String> stickyHeaders)
    {
        this.stickyHeaders.addAll(stickyHeaders);
    }

    public void setClock(final Clock clock)
    {
        this.clock = clock;
    }

    public void setMessaging(final Messaging messaging)
    {
        this.messaging = messaging;
    }

    public void setExecutionPool(final ExecutorService executionPool)
    {
        this.executionPool = executionPool;
    }

    public ExecutorService getExecutionPool()
    {
        return executionPool;
    }

    public void setMessagingPool(final ExecutorService messagingPool)
    {
        this.messagingPool = messagingPool;
    }

    public ExecutorService getMessagingPool()
    {
        return messagingPool;
    }

    public int getExecutionPoolSize()
    {
        return executionPoolSize;
    }

    public void setExecutionPoolSize(int defaultPoolSize)
    {
        this.executionPoolSize = defaultPoolSize;
    }

    public ExecutionObjectCloner getObjectCloner()
    {
        return objectCloner;
    }

    public void setObjectCloner(ExecutionObjectCloner objectCloner)
    {
        this.objectCloner = objectCloner;
    }

    public String runtimeIdentity()
    {
        if (execution == null)
        {
            throw new IllegalStateException("Can only be called after the startup");
        }
        return execution.runtimeIdentity();
    }

    public String getClusterName()
    {
        return clusterName;
    }

    public void setClusterName(final String clusterName)
    {
        this.clusterName = clusterName;
    }

    public String getNodeName()
    {
        return nodeName;
    }

    public void setNodeName(final String nodeName)
    {
        this.nodeName = nodeName;
    }

    public StageMode getMode()
    {
        return mode;
    }

    public void setMode(final StageMode mode)
    {
        if (startCalled)
        {
            throw new IllegalStateException("Stage mode cannot be changed after startup.");
        }
        this.mode = mode;
    }

    public Task<?> start()
    {
        startCalled = true;

        if (clusterName == null || clusterName.isEmpty())
        {
            setClusterName("orbit-cluster");
        }

        if (nodeName == null || nodeName.isEmpty())
        {
            setNodeName(getClusterName());
        }

        if (executionPool == null || messagingPool == null)
        {
            final ExecutorService newService = ExecutorUtils.newScalingThreadPool(executionPoolSize);

            if (executionPool == null)
            {
                executionPool = newService;
            }

            if (messagingPool == null)
            {
                messagingPool = newService;
            }
        }

        if (hosting == null)
        {
            hosting = container == null ? new Hosting() : container.get(Hosting.class);
        }
        if (messaging == null)
        {
            messaging = container == null ? new Messaging() : container.get(Messaging.class);
        }
        if (execution == null)
        {
            execution = container == null ? new Execution() : container.get(Execution.class);
        }
        if (clusterPeer == null)
        {
            if (container != null)
            {
                if (!container.getClasses().stream().filter(ClusterPeer.class::isAssignableFrom).findAny().isPresent())
                {
                    clusterPeer = container.get(JGroupsClusterPeer.class);
                }
                else
                {
                    clusterPeer = container.get(ClusterPeer.class);
                }
            }
            else
            {
                clusterPeer = new JGroupsClusterPeer();
            }
        }
        if (clock == null)
        {
            clock = Clock.systemUTC();
        }
        if (objectCloner == null)
        {
            objectCloner = new KryoCloner();
        }

        if (container != null)
        {
            extensions.addAll(container.getClasses().stream().filter(c -> ActorExtension.class.isAssignableFrom(c) && c.isAnnotationPresent(Singleton.class))
                    .map(c -> (ActorExtension) container.get(c)).collect(Collectors.toList()));
        }

        this.configureOrbitContainer();

        hosting.setNodeType(mode == StageMode.HOST ? NodeCapabilities.NodeTypeEnum.SERVER : NodeCapabilities.NodeTypeEnum.CLIENT);
        execution.setClock(clock);
        execution.setHosting(hosting);
        execution.setMessaging(messaging);
        execution.setExecutor(executionPool);
        execution.setObjectCloner(objectCloner);
        execution.addStickyHeaders(stickyHeaders);

        messaging.setExecution(execution);
        messaging.setClock(clock);
        messaging.setExecutor(messagingPool);

        hosting.setExecution(execution);
        hosting.setClusterPeer(clusterPeer);
        messaging.setClusterPeer(clusterPeer);

        execution.setExtensions(extensions);

        messaging.start();
        hosting.start();
        execution.start();

        Task<?> future = clusterPeer.join(clusterName, nodeName);
        if (mode == StageMode.HOST)
        {
            future = future.thenRun(() -> Actor.getReference(ReminderController.class, "0").ensureStart());
        }

        future = future.thenRun(() ->  bind());

        return future;
    }

    private void configureOrbitContainer()
    {
        // orbitContainer will be null if the application is not using it
        if (container != null)
        {
            // Create a lifetime provider for actor DI
            LifetimeExtension containerLifetime = new LifetimeExtension()
            {
                @Override
                public Task<?> preActivation(AbstractActor<?> actor)
                {
                    container.inject(actor);
                    return Task.done();
                }
            };

            extensions.add(containerLifetime);
        }
    }

    public void setClusterPeer(final ClusterPeer clusterPeer)
    {
        this.clusterPeer = clusterPeer;
    }

    /**
     * Installs extensions to the stage.
     * <p>
     * Example:
     * <pre>
     * stage.addExtension(new MongoDbProvider(...));
     * </pre>
     *
     * @param extension Actor Extensions instance.
     */
    public void addExtension(final ActorExtension extension)
    {
        this.extensions.add(extension);
    }

    public Task<?> stop()
    {
        // * refuse new actor activations
        // first notify other nodes

        // * deactivate all actors
        // * notify rest of the cluster (no more observer messages)
        // * finalize all timers
        // * stop processing new received messages
        // * wait pending tasks execution
        // * stop the network
        return execution.stop()
                .thenRun(clusterPeer::leave);
    }

    /**
     * @deprecated Use #registerObserver instead
     */
    @Deprecated
    public <T extends ActorObserver> T getObserverReference(Class<T> iClass, final T observer)
    {
        return registerObserver(iClass, observer);

    }

    /**
     * @deprecated Use #registerObserver instead
     */
    @Deprecated
    public <T extends ActorObserver> T getObserverReference(final T observer)
    {
        return registerObserver(null, observer);
    }

    public <T extends ActorObserver> T registerObserver(Class<T> iClass, final T observer)
    {
        return execution.getObjectReference(iClass, observer);
    }

    public <T extends ActorObserver> T registerObserver(Class<T> iClass, String id, final T observer)
    {
        return execution.getObserverReference(iClass, observer, id);
    }


    public Hosting getHosting()
    {
        return hosting;
    }

    public ClusterPeer getClusterPeer()
    {
        return clusterPeer != null ? clusterPeer : (clusterPeer = new JGroupsClusterPeer());
    }

    public void cleanup(boolean block)
    {
        if (block)
        {
            execution.activationCleanup().join();
        }
        else
        {
            execution.activationCleanup();
        }
        messaging.timeoutCleanup();
    }

    /**
     * Binds this stage to the current thread.
     * This tells ungrounded references to use this stage to call remote methods.
     * <p>
     * An ungrounded reference is a reference created with {@code Actor.getReference} and used outside of an actor method.
     * <p>
     * This is only necessary when there are <i>two or more</i> OrbitStages active in the same virtual machine and
     * remote calls need to be issued from outside an actor.
     * This method was created to help with test cases.
     * <p>
     * A normal application will have a single stage and should have no reason to call this method.
     * <p>
     * This method writes a weak reference to the runtime in a thread local.
     * No cleanup is necessary, so none is available.
     */
    public void bind()
    {
        execution.bind();
    }

    public List<NodeAddress> getAllNodes()
    {
        if (hosting == null)
        {
            return Collections.emptyList();
        }
        return hosting.getAllNodes();
    }

    public List<NodeAddress> getServerNodes()
    {
        if (hosting == null)
        {
            return Collections.emptyList();
        }
        return hosting.getServerNodes();
    }

    public NodeCapabilities.NodeState getState()
    {
        return execution.getState();
    }

    @ExportMetric(name = "localActorCount")
    public long getLocalActorCount()
    {
        long value = 0;
        if (execution != null)
        {
            value =  execution.getLocalActorCount();
        }

        return value;
    }

    @ExportMetric(name = "messagesReceived")
    public long getMessagesReceived()
    {
        long value = 0;
        if (execution != null)
        {
            value =  execution.getMessagesReceivedCount();
        }

        return value;
    }

    @ExportMetric(name = "messagesHandled")
    public long getMessagesHandled()
    {
        long value = 0;
        if (execution != null)
        {
            value =  execution.getMessagesHandledCount();
        }

        return value;

    }

    @ExportMetric(name = "refusedExecutions")
    public long getRefusedExecutions()
    {
        long value = 0;
        if (execution != null)
        {
            value =  execution.getRefusedExecutionsCount();
        }

        return value;
    }

    public Runtime getRuntime()
    {
        return execution;
    }
}
