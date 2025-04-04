package graphql

import graphql.execution.ExecutionId
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.awaitility.Awaitility
import org.dataloader.DataLoaderRegistry
import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

class ExecutionInputTest extends Specification {

    def query = "query { hello }"
    def registry = new DataLoaderRegistry()
    def root = "root"
    def variables = [key: "value"]

    def "build works"() {
        when:
        def executionInput = ExecutionInput.newExecutionInput().query(query)
                .dataLoaderRegistry(registry)
                .variables(variables)
                .root(root)
                .graphQLContext({ it.of(["a": "b"]) })
                .locale(Locale.GERMAN)
                .extensions([some: "map"])
                .build()
        then:
        executionInput.graphQLContext.get("a") == "b"
        executionInput.root == root
        executionInput.variables == variables
        executionInput.rawVariables.toMap() == variables
        executionInput.dataLoaderRegistry == registry
        executionInput.query == query
        executionInput.locale == Locale.GERMAN
        executionInput.extensions == [some: "map"]
    }

    def "map context build works"() {
        when:
        def executionInput = ExecutionInput.newExecutionInput().query(query)
                .graphQLContext([a: "b"])
                .build()
        then:
        executionInput.graphQLContext.get("a") == "b"
    }

    def "legacy context is defaulted"() {
        // Retaining deprecated method tests for coverage
        when:
        def executionInput = ExecutionInput.newExecutionInput().query(query)
                .build()
        then:
        executionInput.context instanceof GraphQLContext // Retain deprecated for test coverage
        executionInput.getGraphQLContext() == executionInput.getContext() // Retain deprecated for test coverage
    }

    def "graphql context is defaulted"() {
        when:
        def executionInput = ExecutionInput.newExecutionInput().query(query)
                .build()
        then:
        executionInput.graphQLContext instanceof GraphQLContext
    }

    def "locale defaults to JVM default"() {
        when:
        def executionInput = ExecutionInput.newExecutionInput().query(query)
                .build()
        then:
        executionInput.getLocale() == Locale.getDefault()
    }

    def "transform works and copies values"() {
        when:
        def executionInputOld = ExecutionInput.newExecutionInput().query(query)
                .dataLoaderRegistry(registry)
                .variables(variables)
                .extensions([some: "map"])
                .root(root)
                .graphQLContext({ it.of(["a": "b"]) })
                .locale(Locale.GERMAN)
                .build()
        def graphQLContext = executionInputOld.getGraphQLContext()
        def executionInput = executionInputOld.transform({ bldg -> bldg.query("new query") })

        then:
        executionInput.graphQLContext == graphQLContext
        executionInput.root == root
        executionInput.variables == variables
        executionInput.dataLoaderRegistry == registry
        executionInput.locale == Locale.GERMAN
        executionInput.extensions == [some: "map"]
        executionInput.query == "new query"
    }

    def "transform works and sets variables"() {
        when:
        def executionInputOld = ExecutionInput.newExecutionInput().query(query)
                .dataLoaderRegistry(registry)
                .extensions([some: "map"])
                .root(root)
                .graphQLContext({ it.of(["a": "b"]) })
                .locale(Locale.GERMAN)
                .build()
        def graphQLContext = executionInputOld.getGraphQLContext()
        def executionInput = executionInputOld.transform({ bldg ->
            bldg
                    .query("new query")
                    .variables(variables)
        })

        then:
        executionInput.graphQLContext == graphQLContext
        executionInput.root == root
        executionInput.rawVariables.toMap() == variables
        executionInput.dataLoaderRegistry == registry
        executionInput.locale == Locale.GERMAN
        executionInput.extensions == [some: "map"]
        executionInput.query == "new query"
    }

    def "defaults query into builder as expected"() {
        when:
        def executionInput = ExecutionInput.newExecutionInput("{ q }")
                .locale(Locale.ENGLISH)
                .build()
        then:
        executionInput.query == "{ q }"
        executionInput.locale == Locale.ENGLISH
        executionInput.dataLoaderRegistry != null
        executionInput.variables == [:]
    }

    def "integration test so that values make it right into the data fetchers"() {

        def sdl = '''
            type Query {
                fetch : String
            }
        '''
        DataFetcher df = { DataFetchingEnvironment env ->
            return [
                    "locale"        : env.getLocale().getDisplayName(Locale.ENGLISH),
                    "executionId"   : env.getExecutionId().toString(),
                    "graphqlContext": env.getGraphQlContext().get("a")

            ]
        }
        def schema = TestUtil.schema(sdl, ["Query": ["fetch": df]])
        def graphQL = GraphQL.newGraphQL(schema).build()

        when:
        ExecutionInput executionInput = ExecutionInput.newExecutionInput()
                .query("{ fetch }")
                .locale(Locale.GERMAN)
                .executionId(ExecutionId.from("ID123"))
                .build()
        executionInput.getGraphQLContext().putAll([a: "b"])

        def er = graphQL.execute(executionInput)

        then:
        er.errors.isEmpty()
        er.data["fetch"] == "{locale=German, executionId=ID123, graphqlContext=b}"
    }

    def "can cancel the execution"() {
        def sdl = '''
            type Query {
                fetch1 : Inner
                fetch2 : Inner
            }
            
            type Inner {
                f : String
            }
                
        '''

        CountDownLatch fieldLatch = new CountDownLatch(1)

        DataFetcher df1Sec = { DataFetchingEnvironment env ->
            println("Entering DF1")
            return CompletableFuture.supplyAsync {
                println("DF1 async run")
                fieldLatch.await()
                Thread.sleep(1000)
                return [f: "x"]
            }
        }
        DataFetcher df10Sec = { DataFetchingEnvironment env ->
            println("Entering DF10")
            return CompletableFuture.supplyAsync {
                println("DF10 async run")
                fieldLatch.await()
                Thread.sleep(10000)
                return "x"
            }
        }

        def fetcherMap = ["Query": ["fetch1": df1Sec, "fetch2": df1Sec],
                          "Inner": ["f": df10Sec]
        ]
        def schema = TestUtil.schema(sdl, fetcherMap)
        def graphQL = GraphQL.newGraphQL(schema).build()

        when:
        ExecutionInput executionInput = ExecutionInput.newExecutionInput()
                .query("query q { fetch1 { f }  fetch2 { f } }")
                .build()

        def cf = graphQL.executeAsync(executionInput)

        Thread.sleep(250) // let it get into the field fetching say

        // lets cancel it
        println("cancelling")
        executionInput.cancel()

        // let the DFs run
        println("make the fields run")
        fieldLatch.countDown()

        println("and await for the overall CF to complete")
        Awaitility.await().atMost(Duration.ofSeconds(60)).until({ -> cf.isDone() })

        def er = cf.join()

        then:
        !er.errors.isEmpty()
        er.errors[0]["message"] == "Execution has been asked to be cancelled"
    }
}
