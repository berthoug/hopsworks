/*
 * This file is part of Hopsworks
 * Copyright (C) 2020, Logical Clocks AB. All rights reserved
 *
 * Hopsworks is free software: you can redistribute it and/or modify it under the terms of
 * the GNU Affero General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * Hopsworks is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package io.hops.hopsworks.common.python.library;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.core.command.BuildImageResultCallback;
import com.github.dockerjava.core.DockerClientBuilder;
import io.hops.hopsworks.common.dao.command.SystemCommand;
import io.hops.hopsworks.common.dao.python.AnacondaRepo;
import io.hops.hopsworks.common.dao.python.CondaCommandFacade;
import io.hops.hopsworks.common.dao.python.CondaCommands;
import io.hops.hopsworks.common.dao.python.LibraryFacade;
import io.hops.hopsworks.common.dao.python.PythonDep;
import io.hops.hopsworks.common.python.commands.CommandsController;
import io.hops.hopsworks.common.util.ProjectUtils;
import io.hops.hopsworks.exceptions.ServiceException;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.ejb.Singleton;
import javax.ejb.Timeout;
import javax.ejb.TimerConfig;
import javax.ejb.TimerService;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.Startup;

@Singleton
@Startup
@TransactionAttribute(TransactionAttributeType.NEVER)
public class LibraryInstaller {

  private static final Logger LOG = Logger.getLogger(LibraryInstaller.class.getName());

  private static final Comparator ASC_COMPARATOR = new CommandsComparator();

  @Resource
  private TimerService timerService;
  @EJB
  private ProjectUtils projectUtils;
  @EJB
  private CondaCommandFacade condaCommandFacade;
  @EJB
  private CommandsController commandsController;
  @EJB
  private LibraryFacade libraryFacade;

  @PostConstruct
  public void init() {
    schedule();
  }

  private void schedule() {
    timerService.createSingleActionTimer(1000L, new TimerConfig("python library installer", false));
  }

  @Timeout
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public void isAlive() {
    try {
      final List<CondaCommands> allCondaCommands = condaCommandFacade.findByStatus(
          CondaCommandFacade.CondaStatus.NEW);
      allCondaCommands.sort(ASC_COMPARATOR);
      for (final CondaCommands cc : allCondaCommands) {
        try {
          try {
            switch (cc.getOp()) {
              case CREATE:
                createNewImage(cc);
                break;
              case INSTALL:
                installLibrary(cc);
                break;
              case UNINSTALL:
                uninstallLibrary(cc);
                break;
              case REMOVE:
              default:
                throw new UnsupportedOperationException("conda command unknown: " + cc.getInstallType());
            }
          } catch (Throwable ex) {
            LOG.log(Level.WARNING, "Could not execute command with ID: " + cc.getId(), ex);
            commandsController.updateCondaCommandStatus(
                cc.getId(), CondaCommandFacade.CondaStatus.FAILED, cc.getInstallType(), cc.getMachineType(),
                cc.getArg(), cc.getProj(), cc.getUserId(), cc.getOp(), cc.getLib(), cc.getChannelUrl(), cc.getArg());
            continue;
          }
          commandsController.updateCondaCommandStatus(
              cc.getId(), CondaCommandFacade.CondaStatus.SUCCESS, cc.getInstallType(), cc.getMachineType(),
              cc.getArg(), cc.getProj(), cc.getUserId(), cc.getOp(), cc.getLib(), cc.getChannelUrl(), cc.getArg());
        } catch (ServiceException ex) {
          LOG.log(Level.WARNING, "Could not update command with ID: " + cc.getId());
        }
      }
    } finally {
      schedule();
    }
  }

  private void createNewImage(CondaCommands cc) throws IOException {
    File baseDir = new File("/srv/hops/docker");
    baseDir.mkdir();
    File dockerFile = new File(baseDir, "dockerFile_" + cc.getProjectId().getName());
    BufferedWriter writer = new BufferedWriter(new FileWriter(dockerFile));
    writer.write("FROM " + projectUtils.getDockerImageName(cc.getProj()));
    writer.close();

    DockerClient dockerClient = DockerClientBuilder.getInstance().build();

    String imageId = dockerClient.buildImageCmd()
        .withDockerfile(dockerFile)
        .withBaseDirectory(baseDir)
        .withTag("local/" + cc.getProjectId().getName())
        .exec(new BuildImageResultCallback())
        .awaitImageId();

    dockerFile.delete();
  }

  private void installLibrary(CondaCommands cc) throws IOException {
    File baseDir = new File("/srv/hops/docker");
    baseDir.mkdir();
    File dockerFile = new File(baseDir, "dockerFile_" + cc.getProjectId().getName());
    BufferedWriter writer = new BufferedWriter(new FileWriter(dockerFile));
    writer.write("FROM " + projectUtils.getDockerImageName(cc.getProj()) + "\n");
    switch (cc.getInstallType()) {
      case CONDA:
        writer.write("RUN /srv/hops/anaconda/bin/conda install -y -n theenv -c " + cc.getChannelUrl() + " " + cc.
            getLib() + "=" + cc.getVersion());
        break;
      case PIP:
        writer.write("RUN /srv/hops/anaconda/envs/theenv/bin/pip install --upgrade " + cc.getLib() + "==" + cc.
            getVersion());
        break;
      case ENVIRONMENT:
      default:
        throw new UnsupportedOperationException("instal type unknown: " + cc.getInstallType());
    }

    writer.close();

    DockerClient dockerClient = DockerClientBuilder.getInstance().build();

    String imageId = dockerClient.buildImageCmd()
        .withDockerfile(dockerFile)
        .withBaseDirectory(baseDir)
        .withTag("local/" + cc.getProjectId().getName())
        .exec(new BuildImageResultCallback())
        .awaitImageId();

    dockerFile.delete();
  }

  private void uninstallLibrary(CondaCommands cc) throws IOException {
    File baseDir = new File("/srv/hops/docker");
    baseDir.mkdir();
    File dockerFile = new File(baseDir, "dockerFile_" + cc.getProjectId().getName());
    BufferedWriter writer = new BufferedWriter(new FileWriter(dockerFile));
    writer.write("FROM " + projectUtils.getDockerImageName(cc.getProj()) + "\n");
    switch (cc.getInstallType()) {
      case CONDA:
        writer.write("RUN /srv/hops/anaconda/bin/conda remove -y -n theenv " + cc.getLib() + "\n");
        break;
      case PIP:
        writer.write("RUN /srv/hops/anaconda/envs/theenv/bin/pip uninstall -y " + cc.getLib() + "\n");
        break;
      case ENVIRONMENT:
      default:
        throw new UnsupportedOperationException("instal type unknown: " + cc.getInstallType());
    }

    writer.close();

    DockerClient dockerClient = DockerClientBuilder.getInstance().build();

    String imageId = dockerClient.buildImageCmd()
        .withDockerfile(dockerFile)
        .withBaseDirectory(baseDir)
        .withTag("local/" + cc.getProjectId().getName())
        .exec(new BuildImageResultCallback())
        .awaitImageId();

    dockerFile.delete();
  }

  public Collection<PythonDep> listLibraries(String imageName) throws InterruptedException, ServiceException {
    LOG.log(Level.WARNING, "GAUTIER list libraries");
    DockerClient docker = DockerClientBuilder.getInstance().build();
    CreateContainerResponse container = docker
        .createContainerCmd("local/" + imageName)
        .withCmd("conda", "list", "-n", "theenv")
        .withTty(true)
        .exec();

    docker.startContainerCmd(container.getId()).exec();

    LogContainerResultCallback loggingCallback = new LogContainerResultCallback();

    docker
        .logContainerCmd(container.getId())
        .withStdErr(true)
        .withStdOut(true)
        .withFollowStream(true)
        .withTailAll()
        .exec(loggingCallback)
        .awaitStarted();

    loggingCallback.awaitCompletion(60, TimeUnit.SECONDS);

    return depStringToCollec(loggingCallback.toString());
  }

  public Collection<PythonDep> depStringToCollec(String condaListStr) throws ServiceException {
    LOG.log(Level.WARNING, "GAUTIER list libraries str " + condaListStr);
    Collection<PythonDep> deps = new ArrayList();

    String[] lines = condaListStr.split(System.getProperty("line.separator"));

    for (int i = 3; i < lines.length; i++) {

      String line = lines[i];
      LOG.log(Level.WARNING, "GAUTIER parsing line " + line);
      String[] split = line.split(" +");

      String libraryName = split[0];
      String version = split[1];
      String channel = "defaults";
      if (split.length > 3) {
        channel = split[3].trim().isEmpty() ? channel : split[3];
      }
      
      LOG.log(Level.WARNING, "GAUTIER name " + libraryName + " version " + version + " channel:" + channel + "!");
      
      CondaCommandFacade.CondaInstallType instalType = CondaCommandFacade.CondaInstallType.PIP;
      if (!(channel.equals("pypi") || channel.equals("defaults"))) {
        instalType = CondaCommandFacade.CondaInstallType.CONDA;
      }
      AnacondaRepo repo = libraryFacade.getRepo(channel, true);
      boolean cannotBeRemoved = channel.equals("default") ? true : false;
      PythonDep pyDep = libraryFacade.getOrCreateDep(repo, LibraryFacade.MachineType.ALL,
          instalType, libraryName, version, false, cannotBeRemoved);
      deps.add(pyDep);
    }
    return deps;
  }

  private static class CommandsComparator<T> implements Comparator<T> {

    @Override
    public int compare(T t, T t1) {

      if ((t instanceof CondaCommands) && (t1 instanceof CondaCommands)) {
        return condaCommandCompare((CondaCommands) t, (CondaCommands) t1);
      } else if ((t instanceof SystemCommand) && (t1 instanceof SystemCommand)) {
        return systemCommandCompare((SystemCommand) t, (SystemCommand) t1);
      } else {
        return 0;
      }
    }

    private int condaCommandCompare(final CondaCommands t, final CondaCommands t1) {
      if (t.getId() > t1.getId()) {
        return 1;
      } else if (t.getId() < t1.getId()) {
        return -1;
      } else {
        return 0;
      }
    }

    private int systemCommandCompare(final SystemCommand t, final SystemCommand t1) {
      if (t.getId() > t1.getId()) {
        return 1;
      } else if (t.getId() < t1.getId()) {
        return -1;
      } else {
        return 0;
      }
    }
  }
}
