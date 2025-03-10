/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.camunda.zeebe.model.bpmn.builder;

import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;

public class ZeebeExecutionListenersBuilderImpl<B extends AbstractBaseElementBuilder<?, ?>>
    implements ZeebeExecutionListenersBuilder<B> {

  private final B elementBuilder;

  public ZeebeExecutionListenersBuilderImpl(final B elementBuilder) {
    this.elementBuilder = elementBuilder;
  }

  @Override
  public B zeebeStartExecutionListener(final String type, final String retries) {
    final ZeebeExecutionListeners executionListeners =
        elementBuilder.getCreateSingleExtensionElement(ZeebeExecutionListeners.class);
    final ZeebeExecutionListener listener =
        elementBuilder.createChild(executionListeners, ZeebeExecutionListener.class);
    listener.setEventType(ZeebeExecutionListenerEventType.start);
    listener.setType(type);
    listener.setRetries(retries);

    return elementBuilder;
  }

  @Override
  public B zeebeStartExecutionListener(final String type) {
    return zeebeStartExecutionListener(type, ZeebeExecutionListener.DEFAULT_RETRIES);
  }

  @Override
  public B zeebeEndExecutionListener(final String type, final String retries) {
    final ZeebeExecutionListeners executionListeners =
        elementBuilder.getCreateSingleExtensionElement(ZeebeExecutionListeners.class);
    final ZeebeExecutionListener listener =
        elementBuilder.createChild(executionListeners, ZeebeExecutionListener.class);
    listener.setEventType(ZeebeExecutionListenerEventType.end);
    listener.setType(type);
    listener.setRetries(retries);

    return elementBuilder;
  }

  @Override
  public B zeebeEndExecutionListener(final String type) {
    return zeebeEndExecutionListener(type, ZeebeExecutionListener.DEFAULT_RETRIES);
  }
}
