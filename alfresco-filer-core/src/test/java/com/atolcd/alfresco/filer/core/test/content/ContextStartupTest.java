package com.atolcd.alfresco.filer.core.test.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.atolcd.alfresco.filer.core.test.framework.TestApplicationContext;
import com.atolcd.alfresco.filer.core.test.framework.TestAuthentication;

@TestApplicationContext
@TestAuthentication(AuthenticationUtil.SYSTEM_USER_NAME)
@Transactional
public class ContextStartupTest {

  @Autowired
  private NodeService nodeService;

  @Test
  public void basicWriteOperations() {
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
    String rootNodeName = UUID.randomUUID().toString();

    NodeRef rootNodeRef = nodeService.getRootNode(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
    nodeService.setProperty(rootNodeRef, ContentModel.PROP_NAME, rootNodeName);

    assertThat(nodeService.getProperty(rootNodeRef, ContentModel.PROP_NAME)).isEqualTo(rootNodeName);
  }
}
