/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.model.internal.inspect;

import org.gradle.internal.BiActions;
import org.gradle.model.internal.core.*;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;
import org.gradle.model.internal.manage.instance.ManagedProxyFactory;
import org.gradle.model.internal.manage.projection.ManagedModelProjection;
import org.gradle.model.internal.manage.schema.ModelProperty;
import org.gradle.model.internal.manage.schema.ModelSchema;
import org.gradle.model.internal.manage.schema.ModelSchemaStore;
import org.gradle.model.internal.manage.schema.ModelStructSchema;
import org.gradle.model.internal.type.ModelType;

import java.util.Collections;
import java.util.List;

public class ManagedModelInitializer<T> implements NodeInitializer {

    public static final ManagedProxyFactory PROXY_FACTORY = new ManagedProxyFactory();
    private final ModelStructSchema<T> modelSchema;
    private final ModelSchemaStore schemaStore;

    public ManagedModelInitializer(ModelStructSchema<T> modelSchema, ModelSchemaStore schemaStore) {
        this.modelSchema = modelSchema;
        this.schemaStore = schemaStore;
    }

    @Override
    public List<? extends ModelReference<?>> getInputs() {
        return Collections.emptyList();
    }

    @Override
    public void execute(MutableModelNode modelNode, List<ModelView<?>> inputs) {
        for (ModelProperty<?> property : modelSchema.getProperties().values()) {
            addPropertyLink(modelNode, property);
        }
    }

    @Override
    public List<? extends ModelProjection> getProjections() {
        return Collections.singletonList(new ManagedModelProjection<T>(modelSchema, schemaStore, PROXY_FACTORY));
    }

    private <P> void addPropertyLink(MutableModelNode modelNode, ModelProperty<P> property) {
        ModelType<P> propertyType = property.getType();
        ModelSchema<P> propertySchema = schemaStore.getSchema(propertyType);

        final ModelRuleDescriptor descriptor = modelNode.getDescriptor();
        if (propertySchema.getKind().isManaged()) {
            if (!property.isWritable()) {
                ModelCreator creator = ManagedModelCreators.creator(descriptor, modelNode.getPath().child(property.getName()), propertySchema);
                modelNode.addLink(creator);
            } else {
                ModelStructSchema<P> structSchema = (ModelStructSchema<P>) propertySchema;
                ModelProjection projection = new ManagedModelProjection<P>(structSchema, schemaStore, new ManagedProxyFactory());
                ModelCreator creator = ModelCreators.of(modelNode.getPath().child(property.getName()), BiActions.doNothing())
                    .withProjection(projection)
                    .descriptor(descriptor).build();
                modelNode.addReference(creator);
            }
        } else {
            ModelProjection projection = new UnmanagedModelProjection<P>(propertyType, true, true);
            ModelCreator creator = ModelCreators.of(modelNode.getPath().child(property.getName()), BiActions.doNothing())
                .withProjection(projection)
                .descriptor(descriptor).build();
            modelNode.addLink(creator);
        }
    }
}
