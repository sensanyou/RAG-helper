package com.rag.studyhelper.service;

import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.Documents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentMetadataServiceTest {

    @Mock
    private DocumentsMapper documentsMapper;

    @Mock
    private DocumentChunksMapper documentChunksMapper;

    @Test
    void saveAssignsGeneratedDocumentIdToEveryChunk() {
        DocumentMetadataService service = new DocumentMetadataService(documentsMapper, documentChunksMapper);
        Documents document = new Documents();
        DocumentChunks first = new DocumentChunks();
        DocumentChunks second = new DocumentChunks();

        when(documentsMapper.insert(any(Documents.class))).thenAnswer(invocation -> {
            Documents inserted = invocation.getArgument(0);
            inserted.setId(42L);
            return 1;
        });

        service.save(document, Arrays.asList(first, second));

        assertEquals(42L, first.getDocumentId());
        assertEquals(42L, second.getDocumentId());
        verify(documentChunksMapper, times(2)).insert(any(DocumentChunks.class));
    }

    @Test
    void deleteRemovesChunksBeforeDocument() {
        DocumentMetadataService service = new DocumentMetadataService(documentsMapper, documentChunksMapper);

        service.delete(7L);

        InOrder order = inOrder(documentChunksMapper, documentsMapper);
        order.verify(documentChunksMapper).delete(any());
        order.verify(documentsMapper).deleteById(7L);
    }
}
