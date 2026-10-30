package com.llmall.search.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.*;
import com.llmall.api.client.ShopClient;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.search.controller.SearchController;
import com.llmall.search.es.*;
import com.llmall.search.mapper.*;
import com.llmall.search.service.impl.SearchServiceImpl;
import com.llmall.search.support.EmptyIndexFallbackProbe;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.apache.http.HttpHost;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.elasticsearch.client.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.*;
import org.springframework.data.elasticsearch.core.*;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in component HTTP benchmark: real MVC/controller/service/ES query, private ES coordinates. */
@Tag("integration")
class SearchProbeHttpBenchmarkTest {
    private static final int WORKERS=4, REQUESTS=4000;
    @Configuration @EnableWebMvc static class MvcConfig { }

    @Test void mixedHitsAndMissesWithPrivateRealEsIndex() throws Exception {
        Path output=Paths.get(Objects.requireNonNull(System.getenv("LLMALL_PROBE_BENCH_OUTPUT"),"Set a new benchmark output directory"));
        assertFalse(Files.exists(output),"Use a fresh output directory to preserve prior evidence");
        requireMemory();Files.createDirectories(output);
        String index="llmall_probe_bench_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        assertTrue(index.matches("llmall_probe_bench_[0-9a-f]{12}"));
        Map<String,Object> report=new LinkedHashMap<>();List<Map<String,Object>> phases=new ArrayList<>();
        report.put("index",index);report.put("fixtureDocs",10000);report.put("workers",WORKERS);report.put("requestsPerPhase",REQUESTS);
        report.put("scope","isolated component HTTP, not gateway/deployed application; legacy probe policy replica");
        report.put("redis","mocked hot-word side effect");report.put("mysql","mocked; healthy nonempty index must never call it");
        report.put("indexRetained",true);report.put("javaVersion",System.getProperty("java.version"));
        ObjectMapper json=new ObjectMapper();Logger root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous=root.getLevel();root.setLevel(Level.WARN);
        ExecutorService workers=Executors.newFixedThreadPool(WORKERS);Tomcat server=null;
        try(RestHighLevelClient client=new RestHighLevelClient(RestClient.builder(new HttpHost("127.0.0.1",9200,"http"))
                .setRequestConfigCallback(b->b.setConnectTimeout(1000).setSocketTimeout(3000))
                .setHttpClientConfigCallback(b->b.setDefaultIOReactorConfig(IOReactorConfig.custom().setIoThreadCount(2).build())
                        .setMaxConnTotal(4).setMaxConnPerRoute(4)))) {
            // No IK dependency: this English fixture tests request amplification, not Chinese relevance.
            String mapping="{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},\"mappings\":{"
                    +"\"_meta\":{\"purpose\":\"llmall-probe-benchmark\"},\"properties\":{"
                    +"\"name\":{\"type\":\"text\",\"analyzer\":\"standard\"},\"typeName\":{\"type\":\"text\"},"
                    +"\"area\":{\"type\":\"text\"},\"address\":{\"type\":\"text\"},\"status\":{\"type\":\"integer\"},"
                    +"\"eventTimestamp\":{\"type\":\"date\",\"format\":\"epoch_millis\"},\"hotScore\":{\"type\":\"double\"}}}}";
            Request create=new Request("PUT","/"+index);create.setJsonEntity(mapping);client.getLowLevelClient().performRequest(create);
            long now=System.currentTimeMillis();
            for(int offset=0;offset<10000;offset+=500) {
                requireMemory();
                StringBuilder bulk=new StringBuilder();
                for(int i=offset;i<offset+500;i++) {
                    bulk.append("{\"index\":{\"_id\":\"").append(i+1).append("\"}}\n");
                    bulk.append("{\"shopId\":").append(i+1).append(",\"name\":\"").append(i<5000?"found":"other")
                            .append(" fixture ").append(i).append("\",\"typeId\":1,\"typeName\":\"fixture\",\"area\":\"test\","
                                    +"\"address\":\"fixture road\",\"avgPrice\":1000,\"score\":45,\"comments\":10,\"sold\":10,\"hotScore\":1.0,"
                                    +"\"status\":1,\"eventTimestamp\":").append(now).append("}\n");
                }
                Request request=new Request("POST","/"+index+"/_bulk");request.setJsonEntity(bulk.toString());
                try(java.io.InputStream body=client.getLowLevelClient().performRequest(request).getEntity().getContent()) {
                    assertFalse(json.readTree(body).path("errors").asBoolean(true),"Fixture bulk failed");
                }
            }
            client.getLowLevelClient().performRequest(new Request("POST","/"+index+"/_refresh"));
            ElasticsearchRestTemplate real=new ElasticsearchRestTemplate(client);IndexCoordinates coordinates=IndexCoordinates.of(index);
            ElasticsearchOperations redirected=mock(ElasticsearchOperations.class);
            AtomicInteger searches=new AtomicInteger(),counts=new AtomicInteger();
            when(redirected.search(any(Query.class),eq(ShopSearchDocument.class))).thenAnswer(c->{searches.incrementAndGet();
                return real.search((Query)c.getArgument(0),ShopSearchDocument.class,coordinates);});
            when(redirected.count(any(Query.class),eq(ShopSearchDocument.class))).thenAnswer(c->{counts.incrementAndGet();
                return real.count(c.getArgument(0),ShopSearchDocument.class,coordinates);});
            assertEquals(10000,real.count(new org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder()
                    .withQuery(org.elasticsearch.index.query.QueryBuilders.matchAllQuery()).build(),ShopSearchDocument.class,coordinates));
            ShopSearchDocMapper docs=mock(ShopSearchDocMapper.class);AtomicReference<ISearchService> active=new AtomicReference<>();
            ISearchService delegate=(ISearchService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ISearchService.class},
                    (p,m,args)->m.invoke(active.get(),args));
            SearchController controller=new SearchController(delegate);
            server=new Tomcat();Path base=Files.createTempDirectory("llmall-probe-http-");server.setBaseDir(base.toString());
            server.getConnector().setPort(0);server.getConnector().setProperty("address","127.0.0.1");
            AbstractProtocol<?> protocol=(AbstractProtocol<?>)server.getConnector().getProtocolHandler();
            protocol.setMaxThreads(WORKERS);protocol.setMinSpareThreads(1);protocol.setMaxConnections(8);
            Context context=server.addContext("",base.toString());context.setParentClassLoader(getClass().getClassLoader());
            AnnotationConfigWebApplicationContext web=new AnnotationConfigWebApplicationContext();web.register(MvcConfig.class);
            web.addBeanFactoryPostProcessor(f->f.registerSingleton("searchController",controller));
            Tomcat.addServlet(context,"search",new DispatcherServlet(web)).setLoadOnStartup(1);
            context.addServletMappingDecoded("/","search");server.start();
            report.put("port",server.getConnector().getLocalPort());
            HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build();
            URI hit=URI.create("http://127.0.0.1:"+server.getConnector().getLocalPort()+"/search?keyword=found&page=1&size=10");
            URI miss=URI.create("http://127.0.0.1:"+server.getConnector().getLocalPort()+"/search?keyword=nevermatchtoken&page=1&size=10");
            try(BufferedWriter raw=Files.newBufferedWriter(output.resolve("requests.csv"))) {
                raw.write("round,implementation,sequence,latencyNs,expectedHit\n");
                for(int round=1;round<=3;round++) {
                    for(String mode:round%2==1?List.of("legacy","coalesced"):List.of("coalesced","legacy")) {
                        requireMemory();
                        ShopSearchEngineProperties properties=new ShopSearchEngineProperties();
                        SearchServiceImpl service=new SearchServiceImpl(docs,mock(SearchHotWordMapper.class),mock(SearchHistoryMapper.class),
                                mock(ConsumeRecordMapper.class),mock(StringRedisTemplate.class),mock(ShopClient.class),
                                new EsShopSearchGatewayImpl(redirected,docs,properties),properties,mock(BizMetrics.class));
                        if(mode.equals("legacy"))ReflectionTestUtils.setField(service,"emptyIndexProbe",new LegacyProbe());
                        active.set(service);
                        // Same sequential prewarm for each policy; measured calls begin only after all warm responses finish.
                        for(int i=0;i<500;i++){if(i%100==0)requireMemory();request(http,hit,true,json);request(http,miss,false,json);}
                        searches.set(0);counts.set(0);clearInvocations(redirected);double[] nanos=new double[REQUESTS];
                        CountDownLatch ready=new CountDownLatch(WORKERS),start=new CountDownLatch(1);List<Future<?>> tasks=new ArrayList<>();
                        for(int worker=0;worker<WORKERS;worker++) {
                            final int w=worker;tasks.add(workers.submit(()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));
                                for(int n=0;n<REQUESTS/WORKERS;n++){if(n%100==0)requireMemory();int slot=w*(REQUESTS/WORKERS)+n;
                                    nanos[slot]=request(http,n%2==0?hit:miss,n%2==0,json);}return null;}));
                        }
                        assertTrue(ready.await(5,TimeUnit.SECONDS));long at=System.nanoTime();start.countDown();
                        for(Future<?> task:tasks)task.get(60,TimeUnit.SECONDS);
                        double seconds=(System.nanoTime()-at)/1e9;
                        for(int i=0;i<REQUESTS;i++)raw.write(round+","+mode+","+(i+1)+","+(long)nanos[i]+","+(i%2==0)+"\n");
                        raw.flush();Arrays.sort(nanos);Map<String,Object> phase=new LinkedHashMap<>();
                        phase.put("round",round);phase.put("implementation",mode);phase.put("requests",REQUESTS);phase.put("errors",0);
                        phase.put("elapsedSeconds",seconds);phase.put("rps",REQUESTS/seconds);phase.put("avgMs",Arrays.stream(nanos).average().orElseThrow()/1e6);
                        phase.put("p95Ms",nanos[(int)Math.ceil(REQUESTS*.95)-1]/1e6);phase.put("p99Ms",nanos[(int)Math.ceil(REQUESTS*.99)-1]/1e6);
                        phase.put("esSearches",searches.get());phase.put("esCounts",counts.get());phase.put("freeMiB",freeMiB());phases.add(phase);
                        assertEquals(REQUESTS,searches.get());if(mode.equals("coalesced"))assertTrue(counts.get()<=Math.ceil(seconds/10)+1);
                        else assertTrue(counts.get()>0);verifyNoInteractions(docs);
                    }
                }
            }
            report.put("completed",true);
        } catch(Exception|AssertionError e) {
            report.put("completed",false);report.put("failure",e.getClass().getSimpleName());
            report.put("failureMessage",e.getMessage());
            throw e;
        } finally {
            workers.shutdownNow();boolean stopped=false;
            try {stopped=workers.awaitTermination(5,TimeUnit.SECONDS);}
            finally {
                try {if(server!=null)try {server.stop();}finally {server.destroy();}}
                finally {
                    root.setLevel(previous);report.put("phases",phases);
                    report.put("sampleScope","completed phases only; a failed phase has no full latency statistics");
                    json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(),report);
                }
            }
            assertTrue(stopped,"Benchmark workers failed to terminate");
        }
    }
    private static double request(HttpClient client,URI uri,boolean hit,ObjectMapper json) throws Exception {
        long start=System.nanoTime();HttpResponse<String> response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        double ns=System.nanoTime()-start;assertEquals(200,response.statusCode());JsonNode result=json.readTree(response.body());
        assertTrue(result.path("list").isArray());assertEquals(hit?10:0,result.path("list").size());assertEquals(hit?5000:0,result.path("total").asInt());
        return ns;
    }
    /** Replica of the previous two-volatile-field policy; all other HTTP/service/ES code is identical. */
    static class LegacyProbe extends EmptyIndexFallbackProbe {
        volatile long checkedAt;volatile boolean detected;
        LegacyProbe(){super(Duration.ofSeconds(10));}
        @Override public boolean shouldFallback(BooleanSupplier check){long now=System.currentTimeMillis();if(now-checkedAt<10000)return detected;
            boolean value=check.getAsBoolean();detected=value;checkedAt=now;return value;}
        @Override public void observeNonEmpty(){checkedAt=0;}
    }
    private static long freeMiB(){return ((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getFreePhysicalMemorySize()/1048576;}
    private static void requireMemory(){assertTrue(freeMiB()>=1024,"Host free memory below 1024MiB; stop benchmark");}
}
