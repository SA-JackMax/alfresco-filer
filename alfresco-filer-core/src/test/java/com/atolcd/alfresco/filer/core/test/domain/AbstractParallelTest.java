package com.atolcd.alfresco.filer.core.test.domain;

import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.atolcd.alfresco.filer.core.model.RepositoryNode;
import com.atolcd.alfresco.filer.core.model.impl.RepositoryNodeBuilder;
import com.atolcd.alfresco.filer.core.test.domain.content.model.FilerTestConstants;
import com.atolcd.alfresco.filer.core.test.framework.DocumentLibraryProvider;

/**
 * Provide base class for parallel tests of {@linkplain com.atolcd.alfresco.filer.core.model.FilerAction Filer actions}. Assert
 * parallelism is available on test platform.
 *
 * <p>
 * Parallel tests are not executed concurrently to ensure most resources are available for the test, including multiple physical
 * threads to be sure parallelism is achieved during test.
 * </p>
 */
@Execution(ExecutionMode.SAME_THREAD)
public abstract class AbstractParallelTest extends DocumentLibraryProvider {

  protected static final int NUM_THREAD_TO_LAUNCH = Runtime.getRuntime().availableProcessors() * 2;

  protected static final int MAIN_TASK = 1;
  protected static final int CREATE_TASK = 1;
  protected static final int UPDATE_TASK = 1;
  protected static final int DELETE_TASK = 1;

  private final Logger logger = LoggerFactory.getLogger(getClass());

  @Autowired
  private NodeService nodeService;

  private static ExecutorService executor;

  @BeforeAll
  public static void preconditionAndStartExecutor() {
    // Assert required level of parallelism is available
    assertThat(Runtime.getRuntime().availableProcessors()).isGreaterThan(1);

    executor = Executors.newFixedThreadPool(NUM_THREAD_TO_LAUNCH);
  }

  @AfterAll
  public static void stopExecutor() {
    executor.shutdown();
  }

  protected void execute(final CountDownLatch endingLatch, final Callable<Void> task) {
    executor.submit(() -> {
      try {
        AuthenticationUtil.runAsSystem(() -> task.call());
      } catch (Exception e) { //NOPMD Catch all exceptions that might occur in thread as they will not be thrown to main thread
        logger.error("Parallel testing error", e);
      } finally {
        endingLatch.countDown();
      }
    });
  }

  protected RepositoryNodeBuilder buildNode(final String departmentName, final LocalDateTime date) {
    return buildNode()
        .type(FilerTestConstants.Department.DocumentType.NAME)
        .property(FilerTestConstants.Department.Aspect.PROP_NAME, departmentName)
        .property(FilerTestConstants.ImportedAspect.PROP_DATE, date.atZone(ZoneId.systemDefault()));
  }

  protected void createAndDeleteNodesImpl() throws InterruptedException, BrokenBarrierException {
    String departmentName = randomUUID().toString();
    LocalDateTime date = LocalDateTime.of(2004, 8, 12, 0, 0, 0);

    CyclicBarrier startingBarrier = new CyclicBarrier(CREATE_TASK + DELETE_TASK);
    CyclicBarrier preparationAssertBarrier = new CyclicBarrier(MAIN_TASK + DELETE_TASK);
    CountDownLatch endingLatch = new CountDownLatch(CREATE_TASK + DELETE_TASK);
    AtomicReference<RepositoryNode> createdNode = new AtomicReference<>();
    AtomicReference<RepositoryNode> nodeToDelete = new AtomicReference<>();

    execute(endingLatch, () -> {
      logger.debug("Create task: task started");
      RepositoryNode node = buildNode(departmentName, date).build();

      // Wait for every task to be ready for launching parallel task execution
      startingBarrier.await(10, TimeUnit.SECONDS);

      logger.debug("Create task: node creation start");
      createNode(node);
      logger.debug("Create task: node creation end");
      createdNode.set(node);
      return null;
    });

    execute(endingLatch, () -> {
      logger.debug("Delete task: task started");
      RepositoryNode node = buildNode(departmentName, date)
          // Do not archive node, this could generate contention on creating user trashcan
          .aspect(ContentModel.ASPECT_TEMPORARY)
          .build();

      logger.debug("Delete task: creating node that will be deleted");
      createNode(node);
      nodeToDelete.set(node);

      preparationAssertBarrier.await(10, TimeUnit.SECONDS);
      // Wait for assertion on created nodes taking place in main task
      preparationAssertBarrier.await(10, TimeUnit.SECONDS);

      // Wait for every task to be ready for launching parallel task execution
      startingBarrier.await(10, TimeUnit.SECONDS);

      logger.debug("Delete task: node deletion start");
      deleteNode(node);
      logger.debug("Delete task: node deletion end");
      return null;
    });

    // Wait for node creation to finish and then assert node is indeed created
    preparationAssertBarrier.await();
    assertThat(getPath(nodeToDelete.get())).isEqualTo(buildNodePath(departmentName, date));
    preparationAssertBarrier.await();

    // Wait for every task to finish job before asserting results
    endingLatch.await();

    logger.debug("All tasks are done, starting assertions");

    // Assert all tasks were ready for parallel task execution
    assertThat(startingBarrier.isBroken()).isFalse();

    assertThat(getPath(createdNode.get())).isEqualTo(buildNodePath(departmentName, date));

    assertThat(nodeService.exists(nodeToDelete.get().getNodeRef())).isFalse();
    NodeRef nodeToDeleteParent = nodeToDelete.get().getParent();
    if (nodeToDeleteParent.equals(createdNode.get().getParent())) {
      assertThat(nodeService.exists(nodeToDeleteParent)).isTrue();
      NodeRef nodeToDeleteGrandParent = nodeService.getPrimaryParent(nodeToDeleteParent).getParentRef();
      assertThat(nodeService.exists(nodeToDeleteGrandParent)).isTrue();
    } else {
      assertThat(nodeService.exists(nodeToDeleteParent)).isFalse();
    }
  }
}
