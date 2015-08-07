/* Licensed under the Apache License, Version 2.0 (the "License");
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

package org.camunda.bpm.engine.impl.bpmn.behavior;

import static org.camunda.bpm.engine.impl.bpmn.helper.CompensationUtil.SIGNAL_COMPENSATION_DONE;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.impl.ProcessEngineLogger;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.camunda.bpm.engine.impl.bpmn.parser.ErrorEventDefinition;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.persistence.entity.CompensateEventSubscriptionEntity;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.pvm.PvmActivity;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.camunda.bpm.engine.impl.pvm.delegate.SubProcessActivityBehavior;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.camunda.bpm.engine.impl.tree.Collector;
import org.camunda.bpm.engine.impl.tree.FlowScopeWalker;
import org.camunda.bpm.engine.impl.tree.TreeWalker;
import org.camunda.bpm.engine.impl.tree.TreeWalker.WalkCondition;


/**
 * Denotes an 'activity' in the sense of BPMN 2.0:
 * a parent class for all tasks, subprocess and callActivity.
 *
 * @author Joram Barrez
 * @author Daniel Meyer
 * @author Thorben Lindhauer
 */
public class AbstractBpmnActivityBehavior extends FlowNodeActivityBehavior {

  protected static final BpmnBehaviorLogger LOG = ProcessEngineLogger.BEHAVIOR_LOGGER;

  /**
   * Subclasses that call leave() will first pass through this method, before
   * the regular {@link FlowNodeActivityBehavior#leave(ActivityExecution)} is
   * called.
   */
  @Override
  protected void leave(ActivityExecution execution) {
    PvmActivity currentActivity = execution.getActivity();
    ActivityImpl compensationHandler = ((ActivityImpl) currentActivity).findCompensationHandler();

    // subscription for compensation event subprocess is already created
    if(compensationHandler != null && !isCompensationEventSubprocess(compensationHandler)) {
      createCompensateEventSubscription(execution, compensationHandler);
    }
    super.leave(execution);
  }

  protected boolean isCompensationEventSubprocess(ActivityImpl activity) {
    return activity.isCompensationHandler() && activity.isSubProcessScope();
  }

  protected void createCompensateEventSubscription(ActivityExecution execution, ActivityImpl compensationHandler) {
    // the compensate event subscription is created at subprocess or miBody of the the current activity
    PvmActivity currentActivity = execution.getActivity();
    ActivityExecution scopeExecution = execution.findExecutionForFlowScope(currentActivity.getFlowScope());

    CompensateEventSubscriptionEntity.createAndInsert((ExecutionEntity) scopeExecution, compensationHandler);
  }

  protected void propagateExceptionAsError(Exception exception, ActivityExecution execution) throws Exception {
    if (isProcessEngineExceptionWithoutCause(exception) || isTransactionNotActive()) {
      throw exception;
    } else {
      propagateError(null, exception, execution);
    }
  }

  /**
   * Takes an {@link ActivityExecution} and an {@link Callable} and wraps
   * the call to the Callable with the proper error propagation. This method
   * also makes sure that exceptions not caught by following activities in the
   * process will be thrown and not propagated.
   *
   * @param execution
   * @param toExecute
   * @throws Exception
   */
  protected void executeWithErrorPropagation(ActivityExecution execution, Callable<Void> toExecute) throws Exception {
    String activityInstanceId = execution.getActivityInstanceId();
    try {
      toExecute.call();
    } catch (Exception ex) {
      if (activityInstanceId.equals(execution.getActivityInstanceId())) {
        propagateException(execution, ex);
      } else {
        throw ex;
      }
    }
  }

  /**
   * Decides how to propagate the exception properly, e.g. as bpmn error or "normal" error.
   * @param execution the current execution
   * @param ex the exception to propagate
   * @throws Exception if no error handler could be found
   */
  protected void propagateException(ActivityExecution execution, Exception ex) throws Exception {
    BpmnError bpmnError = checkIfCauseOfExceptionIsBpmnError(ex);
    if (bpmnError != null) {
      propagateBpmnError(bpmnError, execution);
    } else {
      propagateExceptionAsError(ex, execution);
    }
  }

  /**
   * Searches recursively through the exception to see if the exception itself
   * or one of its causes is a {@link BpmnError}.
   *
   * @param e
   *          the exception to check
   * @return the BpmnError that was the cause of this exception or null if no
   *         BpmnError was found
   */
  protected BpmnError checkIfCauseOfExceptionIsBpmnError(Throwable e) {
    if (e instanceof BpmnError) {
      return (BpmnError) e;
    } else if (e.getCause() == null) {
      return null;
    }
    return checkIfCauseOfExceptionIsBpmnError(e.getCause());
  }

  protected boolean isTransactionNotActive() {
    return !Context.getCommandContext().getTransactionContext().isTransactionActive();
  }

  protected boolean isProcessEngineExceptionWithoutCause(Exception exception) {
    return exception instanceof ProcessEngineException && exception.getCause() == null;
  }

  protected void propagateBpmnError(BpmnError error, ActivityExecution execution) throws Exception {
    propagateError(error.getErrorCode(), null, execution);
  }

  protected void propagateError(String errorCode, Exception origException, ActivityExecution execution) throws Exception {

    // walk the hierarchy of process instances (cf call activity);
    // this walker walks from leaf execution to leaf execution (i.e. visits one execution per process instance)
    LeafExecutionHierarchyWalker scopeHierarchyWalker = new LeafExecutionHierarchyWalker((PvmExecutionImpl) execution);

    // collectors
    // the error declaration finder internally walks the flow scope hierarchy of the current process instance and searches for an error handler
    ErrorDeclarationFinder errorDeclarationFinder = new ErrorDeclarationFinder(origException, errorCode);
    ProcessInstanceCollector processInstanceCollector = new ProcessInstanceCollector();

    // walk
    scopeHierarchyWalker.addPreCollector(errorDeclarationFinder);
    scopeHierarchyWalker.addPreCollector(processInstanceCollector);
    scopeHierarchyWalker.walkUntil(errorDeclarationFinder.declarationFound());

    PvmActivity errorHandlingActivity = errorDeclarationFinder.getErrorHandlerActivity();
    ErrorEventDefinition errorDefinition = errorDeclarationFinder.getErrorEventDefinition();
    PvmExecutionImpl errorHandlingExecution = errorDeclarationFinder.getErrorHandlingExecution();

    // map variables to super executions in the hierarchy of called process instances
    List<PvmExecutionImpl> processInstanceHierarchy = processInstanceCollector.getProcessInstanceHierarchy();
    for (int i = 0; i < processInstanceHierarchy.size() - 1; i++) {
      PvmExecutionImpl processInstance = processInstanceHierarchy.get(i);
      PvmExecutionImpl superExecution = processInstance.getSuperExecution();
      ActivityImpl activity = superExecution.getActivity();
      SubProcessActivityBehavior subProcessActivityBehavior = (SubProcessActivityBehavior) activity.getActivityBehavior();
      subProcessActivityBehavior.completing(superExecution, processInstance);
    }

    // process the error
    if (errorHandlingExecution == null) {
      if (origException == null) {

        LOG.logMissingBoundaryCatchEvent(execution.getActivity().getId(), errorCode);
        execution.end(true);
      } else {
        // throw original exception
        throw origException;
      }
    }
    else {
      if(errorDefinition.getErrorCodeVariable() != null){
        errorHandlingExecution.setVariable(errorDefinition.getErrorCodeVariable(), errorCode);
      }
      errorHandlingExecution.executeActivity(errorHandlingActivity);
    }
  }

  /**
   * Assumption: execution is executing a transition or an activity.
   *
   * @return the scope for the transition or activity the execution is currently executing
   */
  protected static ScopeImpl getCurrentFlowScope(ActivityExecution execution) {
    ScopeImpl scope = null;
    if(execution.getTransition() != null) {
      // error may be thrown from a sequence flow listener(?)
      scope = execution.getTransition().getDestination().getFlowScope();
    }
    else {
      scope = (ScopeImpl) execution.getActivity();
    }

    // the execution may currently not be executing a scope activity
    scope = scope.isScope() ? scope : scope.getFlowScope();

    return scope;
  }

  @Override
  public void signal(ActivityExecution execution, String signalName, Object signalData) throws Exception {
    if(SIGNAL_COMPENSATION_DONE.equals(signalName)) {
      signalCompensationDone(execution);
    } else {
      super.signal(execution, signalName, signalData);
    }
  }

  protected void signalCompensationDone(ActivityExecution execution) {
    // default behavior is to join compensating executions and propagate the signal if all executions have compensated

    // only wait for non-event-scope executions cause a compensation event subprocess consume the compensation event and
    // do not have to compensate embedded subprocesses (which are still non-event-scope executions)

    if(((PvmExecutionImpl) execution).getNonEventScopeExecutions().isEmpty()) {
      if(execution.getParent() != null) {
        ActivityExecution parent = execution.getParent();
        execution.remove();
        parent.signal(SIGNAL_COMPENSATION_DONE, null);
      }
    } else {
      ((ExecutionEntity)execution).forceUpdate();
    }

  }

  /**
   * In a hierarchy of process instances, visits all leaf executions (i.e. those that are a super execution)
   * from bottom to top.
   */
  public static class LeafExecutionHierarchyWalker extends TreeWalker<PvmExecutionImpl> {

    public LeafExecutionHierarchyWalker(PvmExecutionImpl initialElement) {
      super(initialElement);
    }

    @Override
    protected PvmExecutionImpl nextElement() {
      return currentElement.getProcessInstance().getSuperExecution();
    }

  }

  public static class ErrorDeclarationFinder implements Collector<PvmExecutionImpl> {

    protected Exception exception;
    protected String errorCode;
    protected ErrorDeclarationForProcessInstanceFinder currentProcessInstanceErrorFinder;
    protected Map<ScopeImpl, PvmExecutionImpl> currentProcessInstanceScopeExecutionMapping;

    public ErrorDeclarationFinder(Exception exception, String errorCode) {
      this.exception = exception;
      this.errorCode = errorCode;
    }

    public WalkCondition<PvmExecutionImpl> declarationFound() {
      return new WalkCondition<PvmExecutionImpl>() {
        @Override
        public boolean isFulfilled(PvmExecutionImpl element) {
          if (element == null || currentProcessInstanceErrorFinder.getErrorHandlerActivity() != null) {
            return true;
          }
          return false;
        }
      };
    }

    @Override
    public void collect(PvmExecutionImpl obj) {
      // walk the scope hierarchy for the current process instance and search for an error handler
      currentProcessInstanceScopeExecutionMapping = obj.createActivityExecutionMapping();
      ScopeImpl flowScope = getCurrentFlowScope(obj);
      FlowScopeWalker flowScopeWalker = new FlowScopeWalker(flowScope);
      currentProcessInstanceErrorFinder = new ErrorDeclarationForProcessInstanceFinder(exception, errorCode);
      flowScopeWalker.addPreCollector(currentProcessInstanceErrorFinder);
      flowScopeWalker.walkWhile(currentProcessInstanceErrorFinder.declarationFound());
    }

    public ActivityImpl getErrorHandlerActivity() {
      return currentProcessInstanceErrorFinder.getErrorHandlerActivity();
    }

    public PvmExecutionImpl getErrorHandlingExecution() {
      ActivityImpl errorHandlingActivity = getErrorHandlerActivity();
      if (errorHandlingActivity != null) {
        return currentProcessInstanceScopeExecutionMapping.get(errorHandlingActivity.getEventScope());
      }
      else {
        // in case no error handler was found
        return null;
      }
    }

    public ErrorEventDefinition getErrorEventDefinition() {
      return currentProcessInstanceErrorFinder.getErrorEventDefinition();
    }
  }

  public static class ErrorDeclarationForProcessInstanceFinder implements Collector<ScopeImpl> {

    protected Exception exception;
    protected String errorCode;
    protected ActivityImpl errorHandlerActivity;
    protected ErrorEventDefinition errorEventDefinition;

    public ErrorDeclarationForProcessInstanceFinder(Exception exception, String errorCode) {
      this.exception = exception;
      this.errorCode = errorCode;
    }

    public WalkCondition<ScopeImpl> declarationFound() {
      return new WalkCondition<ScopeImpl>() {
        @Override
        public boolean isFulfilled(ScopeImpl element) {
          return element == null || errorHandlerActivity != null;
        }
      };
    }

    @Override
    public void collect(ScopeImpl scope) {
      List<ErrorEventDefinition> errorEventDefinitions = (List) scope.getProperty(BpmnParse.PROPERTYNAME_ERROR_EVENT_DEFINITIONS);
      if(errorEventDefinitions != null) {
        for (ErrorEventDefinition errorEventDefinition : errorEventDefinitions) {
          if ((exception != null && errorEventDefinition.catchesException(exception)) ||
              (exception == null && errorEventDefinition.catchesError(errorCode))) {
            errorHandlerActivity = scope.getProcessDefinition().findActivity(errorEventDefinition.getHandlerActivityId());
            this.errorEventDefinition = errorEventDefinition;
            break;
          }
        }
      }
    }

    public ActivityImpl getErrorHandlerActivity() {
      return errorHandlerActivity;
    }

    public ErrorEventDefinition getErrorEventDefinition() {
      return errorEventDefinition;
    }
  }

  public static class ProcessInstanceCollector implements Collector<PvmExecutionImpl> {

    protected List<PvmExecutionImpl> processInstanceHierarchy = new ArrayList<PvmExecutionImpl>();

    @Override
    public void collect(PvmExecutionImpl obj) {
      processInstanceHierarchy.add(obj.getProcessInstance());
    }

    public List<PvmExecutionImpl> getProcessInstanceHierarchy() {
      return processInstanceHierarchy;
    }

  }

}
