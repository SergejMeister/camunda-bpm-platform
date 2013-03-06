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

package org.camunda.bpm.engine.impl.cmd;

import java.io.Serializable;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;

/**
 * @author Frederik Heremans
 */
public class DeleteHistoricProcessInstanceCmd implements Command<Object>, Serializable {

  private static final long serialVersionUID = 1L;
  protected String processInstanceId;

  public DeleteHistoricProcessInstanceCmd(String processInstanceId) {
    this.processInstanceId = processInstanceId;
  }

  public Object execute(CommandContext commandContext) {
    if (processInstanceId == null) {
      throw new ProcessEngineException("processInstanceId is null");
    }
    // Check if process instance is still running
    HistoricProcessInstance instance = commandContext
      .getHistoricProcessInstanceManager()
      .findHistoricProcessInstance(processInstanceId);
    
    if(instance == null) {
      throw new ProcessEngineException("No historic process instance found with id: " + processInstanceId);
    }
    if(instance.getEndTime() == null) {
      throw new ProcessEngineException("Process instance is still running, cannot delete historic process instance: " + processInstanceId);
    }
    
    commandContext
      .getHistoricProcessInstanceManager()
      .deleteHistoricProcessInstanceById(processInstanceId);
    
    return null;
  }

}