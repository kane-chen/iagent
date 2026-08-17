package io.invest.iagent.rag.chunking.reader;

import io.invest.iagent.rag.model.Document;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
@Service
public class DocumentReaders {

    @Autowired
    private List<DocumentReader> documentReaders ;

    public String read(Document doc){
        if (StringUtils.isBlank(doc.getFilePath())) {
            throw new IllegalArgumentException("Document filePath is blank");
        }
        DocumentReader reader = route(doc.getFilePath()) ;
        if(Objects.nonNull(reader)){
            return reader.read(doc) ;
        }
        // default
        return this.defaultRead(doc.getFilePath()) ;
    }

    private String defaultRead(String path){
        try{
            Path file = Path.of(path);
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private DocumentReader route(String path){
        if(Objects.isNull(documentReaders)){
            return null ;
        }
        String suffix = path.substring(path.lastIndexOf(".")+1).toLowerCase();
        return documentReaders.stream()
                .filter(t->t.supportTypes() != null)
                .filter(t->t.supportTypes().contains(suffix))
                .findFirst().orElse(null) ;
    }

}
