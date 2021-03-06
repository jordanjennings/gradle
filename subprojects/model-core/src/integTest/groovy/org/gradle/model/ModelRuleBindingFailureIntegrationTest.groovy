/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.model
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.EnableModelDsl
/**
 * Tests the information provided when a model rule fails to bind.
 *
 * @see ModelRuleBindingValidationIntegrationTest
 */
class ModelRuleBindingFailureIntegrationTest extends AbstractIntegrationSpec {

    def setup() {
        EnableModelDsl.enable(executer)
    }

    def "unbound rules are reported"() {
        given:
        buildScript """
            class MyPlugin {
                static class MyThing1 {}
                static class MyThing2 {}
                static class MyThing3 {}

                static class Rules extends RuleSource {
                    @Model
                    MyThing1 thing1(MyThing2 thing2) {
                        new MyThing1()
                    }

                    @Mutate
                    void mutateThing2(MyThing2 thing2, MyThing3 thing3n, String someOtherThing, Integer intParam) {
                    }
                }
            }

            apply type: MyPlugin
        """

        when:
        fails "tasks"

        then:
        failure.assertHasCause("""The following model rules could not be applied:
  MyPlugin\$Rules#mutateThing2
    Subject:
       | Found:false | Path:<unspecified> | Type:MyPlugin\$MyThing2 | Description:parameter 1|
    Inputs:
       | Found:false | Path:<unspecified> | Type:MyPlugin\$MyThing3 | Description:parameter 2|
       | Found:false | Path:<unspecified> | Type:java.lang.String | Description:parameter 3|
       | Found:false | Path:<unspecified> | Type:java.lang.Integer | Description:parameter 4|
  MyPlugin\$Rules#thing1
    Inputs:
       | Found:false | Path:<unspecified> | Type:MyPlugin\$MyThing2 | Description:parameter 1|""")
    }

    def "unbound dsl rules are reported"() {
        given:
        buildScript """

            model {
                foo.bar {

                }
            }
        """

        when:
        fails "tasks"

        then:
        failure.assertHasCause("""The following model rules could not be applied:
  model.foo.bar @ ${File.separator}build.gradle line 4, column 17
    Subject:
       | Found:false | Path:foo.bar | Type:java.lang.Object|""")
    }

    def "suggestions are provided for unbound rules"() {
        given:
        buildScript """
            class MyPlugin {
                static class Rules extends RuleSource {
                    @Mutate
                    void addTasks(ModelMap<Task> tasks) {
                        tasks.create("foobar")
                        tasks.create("raboof")
                    }
                }
            }

            apply type: MyPlugin

            model {
                tasks.foonar {
                }
            }
        """

        when:
        fails "tasks"

        then:
        failure.assertHasCause("""The following model rules could not be applied:
  model.tasks.foonar @ ${File.separator}build.gradle line 15, column 17
    Subject:
       | Found:false | Path:tasks.foonar | Type:java.lang.Object | Suggestions:tasks.foobar|""")
    }

    def "ambiguous binding integration test"() {
        given:
        buildScript """
            class Plugin1 {
                static class Rules extends RuleSource {
                    @Model
                    String s1() {
                        "foo"
                    }
                }
            }

            class Plugin2 {
                static class Rules extends RuleSource {
                    @Model
                    String s2() {
                        "bar"
                    }
                }
            }

            class Plugin3 {
                static class Rules extends RuleSource {
                    @Mutate
                    void m(String s) {
                        "foo"
                    }
                }
            }

            apply type: Plugin1
            apply type: Plugin2
            apply type: Plugin3
        """

        when:
        fails "tasks"

        then:
        failure.assertHasDescription("A problem occurred evaluating root project")
        failure.assertHasCause("There is a problem with model rule Plugin3\$Rules#m.")
        failure.assertHasCause("""Type-only model reference of type java.lang.String (parameter 1) is ambiguous as multiple model elements are available for this type:
  - s1 (created by: Plugin1\$Rules#s1)
  - s2 (created by: Plugin2\$Rules#s2)""")
    }

    def "incompatible type binding"() {
        given:
        buildScript """
            class Plugin1 {
                static class Rules extends RuleSource {
                    @Mutate
                    void addTasks(@Path("tasks") Integer s1) {

                    }
                }
            }

            apply type: Plugin1
        """

        when:
        fails "tasks"

        then:
        failure.assertHasCause("There is a problem with model rule Plugin1\$Rules#addTasks.")
        failure.assertHasCause("""Model reference to element 'tasks' with type java.lang.Integer (parameter 1) is invalid due to incompatible types.
This element was created by Project.<init>.tasks() and can be mutated as the following types:
  - org.gradle.model.ModelMap<org.gradle.api.Task>
  - org.gradle.model.collection.CollectionBuilder<org.gradle.api.Task>
  - org.gradle.api.tasks.TaskContainer (or assignment compatible type thereof)""")
    }

    def "unbound inputs for creator are reported"() {
        given:
        buildScript """
            class Rules extends RuleSource {
                @Model
                Integer foo(@Path("bar") Integer bar) {
                    22
                }
            }

            apply type: Rules
        """

        when:
        fails "tasks"

        then:
        failure.assertHasCause("""The following model rules could not be applied:
  Rules#foo
    Inputs:
       | Found:false | Path:bar | Type:java.lang.Integer""")
    }

    def "unbound rule for project that has no needed tasks does not cause error"() {
        when:
        settingsFile << "include 'a', 'b'"
        file("a/build.gradle") << "model { foo {} }"

        then:
        succeeds ":b:dependencies"
        fails ":a:dependencies"
        failure.assertHasDescription("A problem occurred configuring project ':a'")
        failure.assertHasCause("The following model rules could not be applied:")
    }

    def "bound subject with unbound inputs are reported"() {
        given:
        buildScript """
            class MyPlugin {
                static class MyThing1 {}
                static class MyThing2 {}
                static class MyThing3 {}

                static class Rules extends RuleSource {
                    @Model
                    MyThing1 thing1() {
                        new MyThing1()
                    }

                    @Mutate
                    void thing1(MyThing1 MyThing1, MyThing3 thing3) {

                    }

                    @Mutate
                    void mutateThing2(MyThing2 thing2, MyThing3 thing3) {
                    }
                }
            }

            apply type: MyPlugin
        """

        when:
        fails "tasks"

        then:
        failure.assertHasCause("""The following model rules could not be applied:
  MyPlugin\$Rules#mutateThing2
    Subject:
       | Found:false | Path:<unspecified> | Type:MyPlugin\$MyThing2 | Description:parameter 1|
    Inputs:
       | Found:false | Path:<unspecified> | Type:MyPlugin\$MyThing3 | Description:parameter 2|
  MyPlugin\$Rules#thing1
    Subject:
       | Found:true | Path:thing1 | Type:MyPlugin\$MyThing1 | Description:parameter 1|
    Inputs:
       | Found:false | Path:<unspecified> | Type:MyPlugin\$MyThing3 | Description:parameter 2|""")
    }
}
