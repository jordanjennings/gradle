/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.tooling.internal.provider.runner;

import com.google.common.base.Equivalence;
import org.gradle.BuildAdapter;
import org.gradle.StartParameter;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.Transformer;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestExecutionException;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.invocation.BuildActionRunner;
import org.gradle.internal.invocation.BuildController;
import org.gradle.tooling.internal.protocol.test.InternalJvmTestExecutionDescriptor;
import org.gradle.tooling.internal.protocol.test.InternalTestExecutionException;
import org.gradle.tooling.internal.provider.BuildActionResult;
import org.gradle.tooling.internal.provider.PayloadSerializer;
import org.gradle.tooling.internal.provider.TestExecutionRequestAction;
import org.gradle.util.CollectionUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TestExecutionRequestActionRunner implements BuildActionRunner {
    @Override
    public void run(BuildAction action, BuildController buildController) {
        if (!(action instanceof TestExecutionRequestAction)) {
            return;
        }
        final GradleInternal gradle = buildController.getGradle();
        final TestExecutionRequestAction testExecutionRequestAction = (TestExecutionRequestAction) action;
        configureForTestDescriptors(gradle, testExecutionRequestAction);
        configureForTestClasses(gradle, testExecutionRequestAction);

        PayloadSerializer payloadSerializer = gradle.getServices().get(PayloadSerializer.class);

        Throwable failure = null;
        try {
            buildController.run();
        } catch (RuntimeException rex) {
            Throwable throwable = findRootCause(rex);
            if (throwable instanceof InternalTestExecutionException) {
                failure = throwable;
            } else if (throwable instanceof TestExecutionException) {
                failure = new InternalTestExecutionException("Error while running test(s)", throwable);
            } else {
                throw rex;
            }
        }
        BuildActionResult buildActionResult;
        if (failure != null) {
            buildActionResult = new BuildActionResult(null, payloadSerializer.serialize(failure));
        } else {
            buildActionResult = new BuildActionResult(payloadSerializer.serialize(null), null);
        }
        buildController.setResult(buildActionResult);
    }

    private void configureForTestClasses(GradleInternal gradle, final TestExecutionRequestAction testExecutionRequestAction) {
        if(testExecutionRequestAction.getTestExecutionRequest().getTestClassNames().isEmpty()){
            return;
        }
        final Collection<String> testClassNames = testExecutionRequestAction.getTestExecutionRequest().getTestClassNames();
        final Set<String> testTaskPaths = new HashSet<String>();
        gradle.addBuildListener(new BuildAdapter() {
            @Override
            public void projectsEvaluated(Gradle gradle) {
                final Set<Project> allprojects = gradle.getRootProject().getAllprojects();
                for (Project project : allprojects) {
                    final TaskCollection<Test> testTasks = project.getTasks().withType(Test.class);
                    for (Test testTask : testTasks) {
                        addTestClassFilter(testTask, testClassNames);
                    }
                    testTaskPaths.addAll(CollectionUtils.collect(testTasks, new Transformer<String, Test>() {
                        @Override
                        public String transform(Test testTask) {
                            return testTask.getPath();
                        }
                    }));
                }

                addTasksToStartParameter(testExecutionRequestAction.getStartParameter(), testTaskPaths);
            }
        });
    }

    private void addTestClassFilter(Test testTask, Collection<String> testClassNames) {
        for (String testClassName : testClassNames) {
            testTask.getFilter().includeTest(testClassName, null);
        }
    }

    private void addTasksToStartParameter(StartParameter startParameter, Collection<String> taskPathsToAdd) {
        final List<String> givenTaskPaths = startParameter.getTaskNames();

        final Collection<String> allTaskPaths = CollectionUtils.addAll(givenTaskPaths, taskPathsToAdd);
        startParameter.setTaskNames(CollectionUtils.dedup(allTaskPaths, new Equivalence<String>() {
            @Override
            protected boolean doEquivalent(String a, String b) {
                return a.equals(b);
            }

            @Override
            protected int doHash(String s) {
                return s.hashCode();
            }
        }));
    }

    private void configureForTestDescriptors(GradleInternal gradle, final TestExecutionRequestAction testExecutionRequestAction) {
        final Collection<InternalJvmTestExecutionDescriptor> testDescriptors = testExecutionRequestAction.getTestExecutionRequest().getTestExecutionDescriptors();
        gradle.addBuildListener(new BuildAdapter() {
            @Override
            public void projectsEvaluated(Gradle gradle) {
                final List<String> testTaskPaths = org.gradle.util.CollectionUtils.collect(testDescriptors, new Transformer<String, InternalJvmTestExecutionDescriptor>() {
                    @Override
                    public String transform(InternalJvmTestExecutionDescriptor internalJvmTestDescriptor) {
                        return internalJvmTestDescriptor.getTaskPath();
                    }

                });

                addTasksToStartParameter(testExecutionRequestAction.getStartParameter(), testTaskPaths);

                for (final String testTaskPath : testTaskPaths) {
                    gradle.getRootProject().getTasks().findByPath(testTaskPath);
                    final Task task = gradle.getRootProject().getTasks().findByPath(testTaskPath);
                    if (task == null) {
                        throw new TestExecutionException(String.format("Requested test task with path '%s' cannot be found.", testTaskPath));
                    } else if (!(task instanceof Test)) {
                        throw new TestExecutionException(String.format("Task '%s' of type '%s' not supported for executing tests via TestLauncher API.", testTaskPath, task.getClass().getName()));
                    } else {
                        Test testTask = (Test) task;
                        for (InternalJvmTestExecutionDescriptor testDescriptor : testDescriptors) {
                            if (testDescriptor.getTaskPath().equals(testTaskPath)) {
                                final String className = testDescriptor.getClassName();
                                final String methodName = testDescriptor.getMethodName();
                                if (className == null && methodName == null) {
                                    testTask.getFilter().includeTestsMatching("*");
                                } else {
                                    testTask.getFilter().includeTest(className, methodName);
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    private Throwable findRootCause(Exception tex) {
        Throwable t = tex.getCause();
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
