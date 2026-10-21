package com.rag.studyhelper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.Documents;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 文档元数据持久化服务。
 *
 * 只负责本地数据库事务，不包含远程 Embedding 调用，避免长时间占用数据库连接。
 */
@Service
public class DocumentMetadataService {

    private final DocumentsMapper documentsMapper;
    private final DocumentChunksMapper documentChunksMapper;

    public DocumentMetadataService(DocumentsMapper documentsMapper,
                                   DocumentChunksMapper documentChunksMapper) {
        this.documentsMapper = documentsMapper;
        this.documentChunksMapper = documentChunksMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(Documents document, List<DocumentChunks> chunks) {
        documentsMapper.insert(document);
        for (DocumentChunks chunk : chunks) {
            chunk.setDocumentId(document.getId());
            documentChunksMapper.insert(chunk);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long documentId) {
        documentChunksMapper.delete(
                Wrappers.<DocumentChunks>lambdaQuery()
                        .eq(DocumentChunks::getDocumentId, documentId)
        );
        documentsMapper.deleteById(documentId);
    }
}
