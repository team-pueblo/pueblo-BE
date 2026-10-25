package com.pueblo.catalog.data;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="pueblo.catalog.import-file")
public class CatalogImportRunner implements ApplicationRunner {
    private final CatalogImporter importer;
    private final ConfigurableApplicationContext context;
    private final String file;
    public CatalogImportRunner(CatalogImporter importer,ConfigurableApplicationContext context,
            @Value("${pueblo.catalog.import-file}") String file) {
        this.importer=importer;this.context=context;this.file=file;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if(context instanceof org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)
            throw new IllegalStateException("Catalog import requires spring.main.web-application-type=none");
        try(InputStream input=file.equals("demo")?new ClassPathResource("catalog/demo.json").getInputStream():Files.newInputStream(Path.of(file))) {
            int rows=importer.importJson(input.readNBytes(2*1024*1024+1));
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Catalog import completed products={}",rows);
        }
        context.close();
    }
}
