package com.steve.ai.action;

import com.steve.ai.SteveMod;
import com.steve.ai.action.actions.*;
import com.steve.ai.di.ServiceContainer;
import com.steve.ai.di.SimpleServiceContainer;
import com.steve.ai.event.EventBus;
import com.steve.ai.event.SimpleEventBus;
import com.steve.ai.execution.*;
import com.steve.ai.llm.AgentObservation;
import com.steve.ai.llm.ReActEngine;
import com.steve.ai.llm.ResponseParser;
import com.steve.ai.llm.TaskPlanner;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.plugin.ActionRegistry;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * Executes actions for a Steve entity using the plugin-based action system.
 *
 * <p><b>Architecture:</b></p>
 * <ul>
 *   <li>Uses ActionRegistry for dynamic action creation (Factory + Registry patterns)</li>
 *   <li>Uses InterceptorChain for cross-cutting concerns (logging, metrics, events)</li>
 *   <li>Uses AgentStateMachine for explicit state management</li>
 *   <li>Falls back to legacy switch statement if registry lookup fails</li>
 * </ul>
 *
 * @since 1.1.0
 */
public class ActionExecutor {
    private final SteveEntity steve;
    private TaskPlanner taskPlanner;  // Lazy-initialized to avoid loading dependencies on entity creation
    private final Queue<Task> taskQueue;

    private final java.util.List<BaseAction> activeActions = new java.util.concurrent.CopyOnWriteArrayList<>();
    private String currentGoal;
    private int ticksSinceLastAction;
    private BaseAction idleFollowAction;  // Follow player when idle
    private BaseAction blockedAction = null; // Caches the action waiting for an available slot

    // Async planning support (non-blocking LLM calls)
    private CompletableFuture<ResponseParser.ParsedResponse> planningFuture;
    private boolean isPlanning = false;

    // ReAct Agent loop support
    private ReActEngine reactEngine;                        // Active agent (null = simple planning mode)
    private CompletableFuture<Task> reactFuture;           // Pending LLM evaluation
    private boolean isAgentEvaluating = false;             // Guard against concurrent evaluations
    private String lastCompletedActionDesc = "none";       // Fed into observation

    // Agentic loop components
    private final com.steve.ai.agentic.ToolRegistry toolRegistry;
    private com.steve.ai.agentic.GoalDecomposer goalDecomposer;
    private com.steve.ai.agentic.AgentLoopContext currentLoopContext;

    // NEW: Plugin architecture components
    private final ActionContext actionContext;
    private final InterceptorChain interceptorChain;
    private final AgentStateMachine stateMachine;
    private final EventBus eventBus;

    public ActionExecutor(SteveEntity steve) {
        this.steve = steve;
        this.taskPlanner = null;  // Will be initialized when first needed
        this.taskQueue = new LinkedList<>();
        this.ticksSinceLastAction = 0;
        this.idleFollowAction = null;
        this.planningFuture = null;

        // Initialize plugin architecture components
        this.eventBus = new SimpleEventBus();
        this.stateMachine = new AgentStateMachine(eventBus, steve.getSteveName());
        this.interceptorChain = new InterceptorChain();

        // Setup interceptors
        interceptorChain.addInterceptor(new LoggingInterceptor());
        interceptorChain.addInterceptor(new MetricsInterceptor());
        interceptorChain.addInterceptor(new EventPublishingInterceptor(eventBus, steve.getSteveName()));

        // Build action context
        ServiceContainer container = new SimpleServiceContainer();
        this.actionContext = ActionContext.builder()
            .serviceContainer(container)
            .eventBus(eventBus)
            .stateMachine(stateMachine)
            .interceptorChain(interceptorChain)
            .build();

        this.toolRegistry = com.steve.ai.agentic.ToolRegistryImpl.createDefault();
        // goalDecomposer is lazy-initialized (needs async LLM client)

        SteveMod.LOGGER.debug("ActionExecutor initialized with plugin architecture for Steve '{}'",
            steve.getSteveName());
    }
    
    private TaskPlanner getTaskPlanner() {
        if (taskPlanner == null) {
            SteveMod.LOGGER.info("Initializing TaskPlanner for Steve '{}'", steve.getSteveName());
            taskPlanner = new TaskPlanner();
        }
        return taskPlanner;
    }

    /** Public accessor cho SteveEntity để khởi tạo ComplexCommandPlanner */
    public TaskPlanner getTaskPlannerLazy() {
        return getTaskPlanner();
    }

    /**
     * Processes a natural language command using ASYNC non-blocking LLM calls.
     *
     * <p>This method returns immediately and does NOT block the game thread.
     * The LLM response is processed in tick() when the CompletableFuture completes.</p>
     *
     * <p><b>Non-blocking flow:</b></p>
     * <ol>
     *   <li>User sends command</li>
     *   <li>This method starts async LLM call, returns immediately</li>
     *   <li>Game continues running normally (no freeze!)</li>
     *   <li>tick() checks if planning is done</li>
     *   <li>When done, tasks are queued and execution begins</li>
     * </ol>
     *
     * @param command The natural language command from the user
     */
    /**
     * Start the AI AGENT loop for a command (ReAct: Observe → Reason → Act cycle).
     * Unlike processNaturalLanguageCommand which plans once, this method keeps calling
     * the LLM after each action to adaptively decide the next step.
     */
    public void startAgentLoop(String command) {
        // Reject if already running
        if (isPlanning || isAgentEvaluating) {
            sendToGUI(steve.getSteveName(), "Still thinking, please wait...");
            return;
        }

        // Cancel current work
        for (BaseAction action : activeActions) action.cancel();
        activeActions.clear();
        taskQueue.clear();
        if (idleFollowAction != null) { idleFollowAction.cancel(); idleFollowAction = null; }

        currentGoal = command;
        steve.getMemory().setCurrentGoal(command);
        lastCompletedActionDesc = "none (just started)";

        // Create AgentLoopContext
        currentLoopContext = new com.steve.ai.agentic.AgentLoopContext(command);

        // Create ReActEngine with ToolRegistry
        reactEngine = new ReActEngine(steve, getTaskPlanner().getAsyncClientForProvider(
                SteveConfig.AI_PROVIDER.get().toLowerCase()), command, toolRegistry);

        sendToGUI(steve.getSteveName(), "🤖 Agent mode: " + command);

        // Goal decomposition (async if complex)
        if (getGoalDecomposer().needsDecomposition(command)) {
            getGoalDecomposer().decompose(command)
                .thenAccept(subGoals -> {
                    currentLoopContext.memory.setSubGoals(subGoals);
                    triggerReActEvaluation();
                })
                .exceptionally(ex -> {
                    // Fallback: single sub-goal
                    currentLoopContext.memory.setSubGoals(
                        java.util.List.of(new com.steve.ai.agentic.SubGoal(command, "unknown", 1)));
                    triggerReActEvaluation();
                    return null;
                });
        } else {
            // Simple goal: single sub-goal, start immediately
            currentLoopContext.memory.setSubGoals(
                java.util.List.of(new com.steve.ai.agentic.SubGoal(command, "unknown", 1)));
            triggerReActEvaluation();
        }
    }

    /** Lazy getter for GoalDecomposer — initialized on first use */
    private com.steve.ai.agentic.GoalDecomposer getGoalDecomposer() {
        if (goalDecomposer == null) {
            goalDecomposer = new com.steve.ai.agentic.GoalDecomposerImpl(
                getTaskPlanner().getAsyncClientForProvider(SteveConfig.AI_PROVIDER.get().toLowerCase()));
        }
        return goalDecomposer;
    }

    /** Kicks off a new async ReAct evaluation (called after each action or on start) */
    private void triggerReActEvaluation() {
        if (reactEngine == null || reactEngine.isFinished()) return;

        isAgentEvaluating = true;
        AgentObservation obs = (currentLoopContext != null)
            ? AgentObservation.captureWithContext(steve, lastCompletedActionDesc, currentLoopContext)
            : AgentObservation.capture(steve, lastCompletedActionDesc);

        if (currentLoopContext != null) {
            reactFuture = reactEngine.evaluateNextStep(obs, currentLoopContext.memory);
        } else {
            reactFuture = reactEngine.evaluateNextStep(obs);
        }
    }

    public void processNaturalLanguageCommand(String command) {
        SteveMod.LOGGER.info("Steve '{}' processing command (async): {}", steve.getSteveName(), command);

        // Detect player mood and adjust response speed/tone
        com.steve.ai.personality.PlayerMood mood = com.steve.ai.personality.PlayerMoodDetector.detect(command);
        if (mood == com.steve.ai.personality.PlayerMood.URGENT) {
            // Cancel current non-critical actions immediately for urgent commands
            activeActions.removeIf(a -> {
                if (!a.getRequiredSlots().contains(ActionSlot.LOCOMOTION)) return false;
                a.cancel(); return true;
            });
        }

        // If already planning, ignore new commands
        if (isPlanning) {
            SteveMod.LOGGER.warn("Steve '{}' is already planning, ignoring command: {}", steve.getSteveName(), command);
            sendToGUI(steve.getSteveName(), "Hold on, I'm still thinking about the previous command...");
            return;
        }

        // Cancel any current actions
        for (BaseAction action : activeActions) {
            action.cancel();
        }
        activeActions.clear();

        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }

        try {
            // Store command and start async planning
            this.isPlanning = true;

            // Send immediate feedback to user
            sendToGUI(steve.getSteveName(), "Thinking...");

            // Start async LLM call - returns immediately!
            planningFuture = getTaskPlanner().planTasksAsync(steve, command);

            SteveMod.LOGGER.info("Steve '{}' started async planning for: {}", steve.getSteveName(), command);

        } catch (NoClassDefFoundError e) {
            SteveMod.LOGGER.error("Failed to initialize AI components", e);
            sendToGUI(steve.getSteveName(), "Sorry, I'm having trouble with my AI systems!");
            isPlanning = false;
            planningFuture = null;
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error starting async planning", e);
            sendToGUI(steve.getSteveName(), "Oops, something went wrong!");
            isPlanning = false;
            planningFuture = null;
        }
    }

    /**
     * Send a message to both the GUI pane AND the in-game chat.
     * Steve now talks in the real Minecraft chat like a real player.
     */
    private void sendToGUI(String steveName, String message) {
        // GUI panel (client-side)
        if (steve.level().isClientSide) {
            com.steve.ai.client.SteveGUI.addSteveMessage(steveName, message);
        }
        // Server-side: send to all players via game chat
        if (!steve.level().isClientSide) {
            steve.sendChatMessage(message);
        }
    }

    public void tick() {
        ticksSinceLastAction++;

        // Proactive behaviors: auto-torch at night, etc.
        checkEnvironmentProactive();

        // ── ReAct Agent evaluation result check ─────────────────────────────
        if (isAgentEvaluating && reactFuture != null && reactFuture.isDone()) {
            isAgentEvaluating = false;
            try {
                Task nextTask = reactFuture.get();
                reactFuture = null;

                // MAX_STEPS exceeded check
                if (reactEngine != null && reactEngine.getStepCount() >= 12
                        && !reactEngine.isFinished()
                        && nextTask != null) {
                    if (currentLoopContext != null) {
                        currentLoopContext.status = com.steve.ai.agentic.AgentLoopStatus.FAILED;
                        steve.getMemory().getReflectionEngine().recordSession(currentLoopContext);
                        sendToGUI(steve.getSteveName(), "❌ Không hoàn thành được goal.");
                        currentLoopContext = null;
                    }
                    currentGoal = null;
                    reactEngine = null;
                } else if (nextTask == null || (reactEngine != null && reactEngine.isFinished())) {
                    // Loop ended — determine final status
                    if (currentLoopContext != null) {
                        if (currentLoopContext.memory.allSubGoalsDone()) {
                            currentLoopContext.status = com.steve.ai.agentic.AgentLoopStatus.DONE;
                            sendToGUI(steve.getSteveName(), "✅ Xong rồi! Goal hoàn thành.");
                        } else {
                            currentLoopContext.status = com.steve.ai.agentic.AgentLoopStatus.FAILED;
                            sendToGUI(steve.getSteveName(), "❌ Không hoàn thành được goal.");
                        }
                        steve.getMemory().getReflectionEngine().recordSession(currentLoopContext);
                        currentLoopContext = null;
                    }
                    currentGoal = null;
                    reactEngine = null;
                } else {
                    // Queue next action from agent
                    if (currentLoopContext != null) currentLoopContext.totalSteps++;
                    taskQueue.add(nextTask);
                }
            } catch (Exception e) {
                SteveMod.LOGGER.error("[ReAct] Error getting evaluation result", e);
                reactEngine = null;
                isAgentEvaluating = false;
            }
        }

        // Check if async planning is complete (non-blocking check!)
        if (isPlanning && planningFuture != null && planningFuture.isDone()) {
            try {
                ResponseParser.ParsedResponse response = planningFuture.get();

                if (response != null) {
                    currentGoal = response.getPlan();
                    steve.getMemory().setCurrentGoal(currentGoal);

                    taskQueue.clear();
                    taskQueue.addAll(response.getTasks());

                    if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(steve.getSteveName(), "Okay! " + currentGoal);
                    }

                    SteveMod.LOGGER.info("Steve '{}' async planning complete: {} tasks queued",
                        steve.getSteveName(), taskQueue.size());
                } else {
                    sendToGUI(steve.getSteveName(), "I couldn't understand that command.");
                    SteveMod.LOGGER.warn("Steve '{}' async planning returned null response", steve.getSteveName());
                }

            } catch (java.util.concurrent.CancellationException e) {
                SteveMod.LOGGER.info("Steve '{}' planning was cancelled", steve.getSteveName());
                sendToGUI(steve.getSteveName(), "Planning cancelled.");
            } catch (Exception e) {
                SteveMod.LOGGER.error("Steve '{}' failed to get planning result", steve.getSteveName(), e);
                sendToGUI(steve.getSteveName(), "Oops, something went wrong while planning!");
            } finally {
                isPlanning = false;
                planningFuture = null;
            }
        }

        java.util.Iterator<BaseAction> it = activeActions.iterator();
        while (it.hasNext()) {
            BaseAction action = it.next();
            if (action.isComplete()) {
                ActionResult result = action.getResult();
                SteveMod.LOGGER.info("Steve '{}' - Action completed: {} (Success: {})", 
                    steve.getSteveName(), result.getMessage(), result.isSuccess());
                
                steve.getMemory().addAction(action.getDescription());
                lastCompletedActionDesc = action.getDescription() + " → " + result.getMessage();

                // Báo kết quả cho gRPC Hybrid Brain (Critic feedback)
                steve.reportTaskResult(action.getDescription(), result.isSuccess(),
                    result.isSuccess() ? null : result.getMessage());
                
                if (!result.isSuccess() && result.requiresReplanning()) {
                    if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(steve.getSteveName(), "Problem: " + result.getMessage());
                    }
                    // Self-reflection: ghi nhận lỗi để học
                    steve.getMemory().getReflectionEngine()
                        .recordFailure(action.getDescription().split(" ")[0], result.getMessage());
                }
                
                activeActions.remove(action);

                // ReplanTrigger: decide CONTINUE / REPLAN / ABORT
                if (reactEngine != null && !reactEngine.isFinished() && currentLoopContext != null) {
                    AgentObservation replanObs = AgentObservation.captureWithContext(
                        steve, lastCompletedActionDesc, currentLoopContext);
                    com.steve.ai.agentic.ReplanTrigger trigger = new com.steve.ai.agentic.ReplanTriggerImpl();
                    com.steve.ai.agentic.ReplanDecision decision = trigger.evaluate(
                        result, currentLoopContext.memory, replanObs);

                    if (decision == com.steve.ai.agentic.ReplanDecision.ABORT) {
                        currentLoopContext.status = com.steve.ai.agentic.AgentLoopStatus.ABORTED;
                        steve.getMemory().getReflectionEngine().recordSession(currentLoopContext);
                        sendToGUI(steve.getSteveName(), "Tao bị kẹt rồi, không làm được nữa!");
                        reactEngine = null;
                        currentLoopContext = null;
                        currentGoal = null;
                        stopCurrentAction();
                        break; // exit the while loop; skip rest of tick processing
                    } else if (decision == com.steve.ai.agentic.ReplanDecision.REPLAN) {
                        if (!isAgentEvaluating) {
                            taskQueue.clear();
                            blockedAction = null;
                            isAgentEvaluating = true;
                            AgentObservation replanObs2 = AgentObservation.captureWithContext(
                                steve, lastCompletedActionDesc, currentLoopContext);
                            reactFuture = reactEngine.forceReplan(replanObs2, currentLoopContext.memory, result.getMessage());
                        }
                    } else { // CONTINUE
                        currentLoopContext.memory.incrementAttempt(action.getDescription().split(" ")[0]);
                    }
                }

                // ReAct: after this action done, if queue is now empty → ask LLM what to do next
                if (reactEngine != null && !reactEngine.isFinished()
                        && !isAgentEvaluating
                        && taskQueue.isEmpty()
                        && activeActions.isEmpty()) {
                    triggerReActEvaluation();
                }

            } else {
                if (ticksSinceLastAction % 100 == 0) {
                    SteveMod.LOGGER.info("Steve '{}' - Ticking action: {}", 
                        steve.getSteveName(), action.getDescription());
                }
                action.tick();
            }
        }

        if (ticksSinceLastAction >= SteveConfig.ACTION_TICK_DELAY.get()) {
            while (!taskQueue.isEmpty() || blockedAction != null) {
                BaseAction prospectiveAction;
                if (blockedAction != null) {
                    prospectiveAction = blockedAction;
                } else {
                    Task pendingTask = taskQueue.peek();
                    prospectiveAction = createAction(pendingTask);
                    if (prospectiveAction == null) {
                        taskQueue.poll(); // Remove invalid action
                        continue;
                    }
                }
                
                java.util.EnumSet<ActionSlot> required = prospectiveAction.getRequiredSlots();
                boolean slotsFree = true;
                for (BaseAction active : activeActions) {
                    java.util.EnumSet<ActionSlot> occupied = active.getRequiredSlots();
                    for (ActionSlot req : required) {
                        if (occupied.contains(req)) {
                            slotsFree = false;
                            break;
                        }
                    }
                    if (!slotsFree) break;
                }
                
                if (slotsFree) {
                    if (blockedAction == null) {
                        taskQueue.poll(); // Safe removal
                    }
                    blockedAction = null;
                    executeTask(prospectiveAction);
                    ticksSinceLastAction = 0;
                } else {
                    // Stop trying to schedule tasks if the first one is blocked, to preserve logical ordering
                    blockedAction = prospectiveAction;
                    break;
                }
            }
        }
        
        // When completely idle (no tasks, no goal), follow nearest player
        if (taskQueue.isEmpty() && activeActions.isEmpty() && currentGoal == null) {
            if (idleFollowAction == null) {
                idleFollowAction = new IdleFollowAction(steve);
                idleFollowAction.start();
            } else if (idleFollowAction.isComplete()) {
                // Restart idle following if it stopped
                idleFollowAction = new IdleFollowAction(steve);
                idleFollowAction.start();
            } else {
                // Continue idle following
                idleFollowAction.tick();
            }
        } else if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
    }

    private void executeTask(BaseAction action) {
        SteveMod.LOGGER.info("Steve '{}' executing action: {}", steve.getSteveName(), action.getDescription());
        activeActions.add(action);
        action.start();
        SteveMod.LOGGER.info("Action started! Is complete: {}", action.isComplete());
    }

    /**
     * Creates an action using the plugin registry with legacy fallback.
     *
     * <p>First attempts to create the action via ActionRegistry (plugin system).
     * If the registry doesn't have the action or creation fails, falls back
     * to the legacy switch statement for backward compatibility.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown action type
     */
    private BaseAction createAction(Task task) {
        String actionType = task.getAction();

        // Try registry-based creation first (plugin architecture)
        ActionRegistry registry = ActionRegistry.getInstance();
        if (registry.hasAction(actionType)) {
            BaseAction action = registry.createAction(actionType, steve, task, actionContext);
            if (action != null) {
                SteveMod.LOGGER.debug("Created action '{}' via registry (plugin: {})",
                    actionType, registry.getPluginForAction(actionType));
                return action;
            }
        }

        // Fallback to legacy switch statement for backward compatibility
        SteveMod.LOGGER.debug("Using legacy fallback for action: {}", actionType);
        return createActionLegacy(task);
    }

    /**
     * Legacy action creation using switch statement.
     *
     * <p>Kept for backward compatibility during migration to plugin system.
     * Will be removed in a future version once all actions are registered
     * via plugins.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown
     * @deprecated Use ActionRegistry instead
     */
    @Deprecated
    private BaseAction createActionLegacy(Task task) {
        return switch (task.getAction()) {
            case "pathfind" -> new PathfindAction(steve, task);
            case "mine"     -> new MineBlockAction(steve, task);
            case "place"    -> new PlaceBlockAction(steve, task);
            case "craft"    -> new CraftItemAction(steve, task);
            case "smelt"    -> new SmeltItemAction(steve, task);
            case "farm"     -> new FarmingAction(steve, task);
            case "chest"    -> new ChestAction(steve, task);
            case "attack"   -> new CombatAction(steve, task);
            case "follow"   -> new FollowPlayerAction(steve, task);
            case "gather"   -> new GatherResourceAction(steve, task);
            case "build"    -> new BuildStructureAction(steve, task);
            case "trade"    -> new TradeAction(steve, task);
            case "sleep"    -> new SleepAction(steve, task);
            case "fish"     -> new FishingAction(steve, task);
            case "brew"     -> new BrewingAction(steve, task);
            case "waypoint" -> new WaypointAction(steve, task);
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", task.getAction());
                yield null;
            }
        };
    }

    public void stopCurrentAction() {
        for (BaseAction action : activeActions) {
            action.cancel();
        }
        activeActions.clear();
        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
        taskQueue.clear();
        currentGoal = null;
        blockedAction = null;

        // Reset state machine
        stateMachine.reset();
    }

    /** Enqueue a single task for execution. */
    public void enqueue(Task task) {
        // Prevent spamming the same task consecutively (detect loops)
        if (!taskQueue.isEmpty()) {
            Task lastTask = null;
            if (taskQueue instanceof LinkedList<Task>) {
                lastTask = ((LinkedList<Task>) taskQueue).getLast();
            }
            if (lastTask != null && lastTask.getAction().equals(task.getAction()) 
                    && lastTask.getParameters().equals(task.getParameters())) {
                SteveMod.LOGGER.debug("ActionExecutor: Skipping duplicate task enqueue for {}", task.getAction());
                return;
            }
        }
        taskQueue.add(task);
    }

    /** Clear all pending tasks from the queue. */
    public void clearQueue() {
        taskQueue.clear();
    }

    public boolean isExecuting() {
        return !activeActions.isEmpty() || !taskQueue.isEmpty();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    /**
     * Returns the event bus for subscribing to action events.
     *
     * @return EventBus instance
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * Returns the agent state machine.
     *
     * @return AgentStateMachine instance
     */
    public AgentStateMachine getStateMachine() {
        return stateMachine;
    }

    /**
     * Returns the interceptor chain for adding custom interceptors.
     *
     * @return InterceptorChain instance
     */
    public InterceptorChain getInterceptorChain() {
        return interceptorChain;
    }

    /**
     * Returns the action context.
     *
     * @return ActionContext instance
     */
    public ActionContext getActionContext() {
        return actionContext;
    }

    /**
     * Checks if the agent is currently planning (async LLM call in progress).
     *
     * @return true if planning
     */
    public boolean isPlanning() {
        return isPlanning;
    }

    /** True khi Steve đang thực thi action hoặc đang plan — dùng để tắt idle chat */
    public boolean isCurrentlyBusy() {
        return isPlanning || isAgentEvaluating || !activeActions.isEmpty() || !taskQueue.isEmpty();
    }

    // ── Proactive Behaviors ────────────────────────────────────────────────────

    /**
     * Called from tick() to check environment and act proactively.
     * Currently handles: auto-torch placement at night.
     * Cooldown: đặt đuốc mỗi 3s nhưng chỉ chat nhắc mỗi 30s để tránh spam.
     */
    private int torchChatCooldown = 0; // ticks kể từ lần chat torch cuối

    @SuppressWarnings("null") // Minecraft API (blockPosition, getBrightness, getNearestPlayer) guaranteed non-null at runtime
    private void checkEnvironmentProactive() {
        // Chỉ chạy mỗi 3 giây (60 ticks)
        if (ticksSinceLastAction % 60 != 0) return;
        if (steve.level().isClientSide) return;

        torchChatCooldown = Math.max(0, torchChatCooldown - 60);

        long dayTime = steve.level().getDayTime() % 24000;
        boolean isNight = dayTime >= 13000;

        if (!isNight) return;

        net.minecraft.core.BlockPos stevePos = steve.blockPosition();
        int lightLevel = steve.level().getBrightness(
                net.minecraft.world.level.LightLayer.BLOCK, stevePos);

        if (lightLevel < 7) {
            // Kiểm tra Steve có đuốc trong túi không trước khi đặt
            com.steve.ai.memory.SteveInventory inv = steve.getMemory().getInventory();
            boolean hasTorches = inv.hasItem(net.minecraft.world.item.Items.TORCH, 1);

            if (!hasTorches) {
                // Không có đuốc, tự động enqueue task craft (nếu chưa có trong queue và đang rảnh)
                if (torchChatCooldown == 0 && taskQueue.isEmpty() && activeActions.isEmpty() && blockedAction == null) {
                    steve.sendChatMessage("Tối quá nhưng tao hết đuốc rồi, để tao tự làm!");
                    enqueue(new com.steve.ai.action.Task("craft", java.util.Map.of("item", "torch", "quantity", 4)));
                    torchChatCooldown = 1200; // Cảnh báo/Tự làm mỗi 60 giây
                }
                return;
            }

            int torchesPlaced = 0;
            // Đặt đuốc quanh Steve (tối đa 4 cây, trừ từ inventory)
            net.minecraft.core.BlockPos[] positions = {
                stevePos.north(2), stevePos.south(2),
                stevePos.east(2), stevePos.west(2)
            };
            for (net.minecraft.core.BlockPos pos : positions) {
                if (inv.hasItem(net.minecraft.world.item.Items.TORCH, 1)) {
                    if (placeTorchAt(pos)) {
                        inv.removeItem(net.minecraft.world.item.Items.TORCH, 1);
                        torchesPlaced++;
                    }
                }
            }

            // Đặt đuốc gần player nếu cần
            net.minecraft.world.entity.player.Player player =
                    steve.level().getNearestPlayer(steve, 20.0);
            if (player != null) {
                net.minecraft.core.BlockPos playerPos = player.blockPosition();
                int playerLight = steve.level().getBrightness(
                        net.minecraft.world.level.LightLayer.BLOCK, playerPos);
                if (playerLight < 7) {
                    net.minecraft.core.BlockPos[] playerPositions = {
                        playerPos.north(2), playerPos.south(2),
                        playerPos.east(2), playerPos.west(2)
                    };
                    for (net.minecraft.core.BlockPos pos : playerPositions) {
                        if (inv.hasItem(net.minecraft.world.item.Items.TORCH, 1)) {
                            if (placeTorchAt(pos)) {
                                inv.removeItem(net.minecraft.world.item.Items.TORCH, 1);
                                torchesPlaced++;
                            }
                        }
                    }
                }
            }

            // Chỉ chat khi cooldown hết (30 giây) để tránh spam
            if (torchesPlaced > 0 && torchChatCooldown == 0) {
                steve.sendChatMessage("Tối quá, tao cắm " + torchesPlaced + " đuốc cho sáng nha!");
                torchChatCooldown = 600; // reset 30 giây
                com.steve.ai.SteveMod.LOGGER.info("Steve '{}' placed {} torches (night, light={})",
                        steve.getSteveName(), torchesPlaced, lightLevel);
            }
        }
    }

    /**
     * Đặt đuốc tại vị trí pos nếu hợp lệ (nền solid, không khí phía trên, không đè lên cây/cỏ).
     * @return true nếu đặt thành công
     */
    @SuppressWarnings("null") // Minecraft API (getBlockState, isSolidRender, setBlock) guaranteed non-null at runtime
    private boolean placeTorchAt(net.minecraft.core.BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState atPos = steve.level().getBlockState(pos);
        net.minecraft.world.level.block.state.BlockState below = steve.level().getBlockState(pos.below());

        // Chỉ đặt khi: vị trí là air VÀ nền bên dưới là solid render (không phải cỏ dại, nửa khối lơ lửng)
        if (atPos.isAir() && below.isSolidRender(steve.level(), pos.below())) {
            steve.level().setBlock(pos,
                    net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), 3);
            return true;
        }
        return false;
    }

    /**
     * Returns the description of the currently executing action.
     * @return String description or "none" if idle.
     */
    public String getCurrentActionDescription() {
        if (activeActions.isEmpty()) return "none";
        return activeActions.get(0).getDescription();
    }
}
