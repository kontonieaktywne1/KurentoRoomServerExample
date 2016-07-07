/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kurento.room.internal;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.kurento.client.*;
import org.kurento.room.api.RoomHandler;
import org.kurento.room.exception.RoomException;
import org.kurento.room.exception.RoomException.Code;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Ivan Gracia (izanmail@gmail.com)
 * @author Micael Gallego (micael.gallego@gmail.com)
 * @author Radu Tom Vlad (rvlad@naevatec.com)
 * @since 1.0.0
 */
public class Room {
  public static final int ASYNC_LATCH_TIMEOUT = 30;

  private final static Logger log = LoggerFactory.getLogger(Room.class);

  private final ConcurrentMap<String, Participant> participants = new ConcurrentHashMap<String, Participant>();
  private final String name;

  private MediaPipeline pipeline;
  private CountDownLatch pipelineLatch = new CountDownLatch(1);

  private KurentoClient kurentoClient;

  private RoomHandler roomHandler;
  
  private RecorderEndpoint recorder;
  
  private volatile boolean closed = false;

  private AtomicInteger activePublishers = new AtomicInteger(0);

  private Object pipelineCreateLock = new Object();
  private Object pipelineReleaseLock = new Object();
  private volatile boolean pipelineReleased = false;
  private boolean destroyKurentoClient;

  public Room(String roomName, KurentoClient kurentoClient, RoomHandler roomHandler,
      boolean destroyKurentoClient) {
    this.name = roomName;
    this.kurentoClient = kurentoClient;
    this.destroyKurentoClient = destroyKurentoClient;
    this.roomHandler = roomHandler;
    log.debug("New ROOM instance, named '{}'", roomName);
  }

  public String getName() {
    return name;
  }

  public MediaPipeline getPipeline() {
    try {
      pipelineLatch.await(Room.ASYNC_LATCH_TIMEOUT, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    return this.pipeline;
  }

  public void join(String participantId, String userName, boolean webParticipant)
      throws RoomException {

    checkClosed();

    if (userName == null || userName.isEmpty()) {
      throw new RoomException(Code.GENERIC_ERROR_CODE, "Empty user name is not allowed");
    }
    for (Participant p : participants.values()) {
      if (p.getName().equals(userName)) {
        throw new RoomException(Code.EXISTING_USER_IN_ROOM_ERROR_CODE, "User '" + userName
            + "' already exists in room '" + name + "'");
      }
    }

    createPipeline();

    participants.put(participantId, new Participant(participantId, userName, this, getPipeline(),
        webParticipant));

    log.info("ROOM {}: Added participant {}", name, userName);

    //recordSession(participantId);
  }

  public void newPublisher(Participant participant) {
    registerPublisher();

    // pre-load endpoints to recv video from the new publisher
    for (Participant participant1 : participants.values()) {
      if (participant.equals(participant1)) {
        continue;
      }
      participant1.getNewOrExistingSubscriber(participant.getName());
    }

    log.debug("ROOM {}: Virtually subscribed other participants {} to new publisher {}", name,
        participants.values(), participant.getName());
  }

  public void cancelPublisher(Participant participant) {
    deregisterPublisher();

    // cancel recv video from this publisher
    for (Participant subscriber : participants.values()) {
      if (participant.equals(subscriber)) {
        continue;
      }
      subscriber.cancelReceivingMedia(participant.getName());
    }

    log.debug("ROOM {}: Unsubscribed other participants {} from the publisher {}", name,
        participants.values(), participant.getName());

  }

  public void leave(String participantId) throws RoomException {

    checkClosed();

    Participant participant = participants.get(participantId);
    if (participant == null) {
      throw new RoomException(Code.USER_NOT_FOUND_ERROR_CODE, "User #" + participantId
          + " not found in room '" + name + "'");
    }
    if (participant.isRecording()) {
      stopRecording(participantId);
    }
    log.info("PARTICIPANT {}: Leaving room {}", participant.getName(), this.name);
    if (participant.isStreaming()) {
      this.deregisterPublisher();
    }
    this.removeParticipant(participant);
    participant.close();
  }

  public Collection<Participant> getParticipants() {

    checkClosed();

    return participants.values();
  }

  public Set<String> getParticipantIds() {

    checkClosed();

    return participants.keySet();
  }

  public Participant getParticipant(String participantId) {

    checkClosed();

    return participants.get(participantId);
  }

  public Participant getParticipantByName(String userName) {

    checkClosed();

    for (Participant p : participants.values()) {
      if (p.getName().equals(userName)) {
        return p;
      }
    }

    return null;
  }

  public void close() {
    if (!closed) {

      for (Participant user : participants.values()) {
        user.close();
      }

      participants.clear();

      closePipeline();

      log.debug("Room {} closed", this.name);

      if (destroyKurentoClient) {
        kurentoClient.destroy();
      }

      this.closed = true;
    } else {
      log.warn("Closing an already closed room '{}'", this.name);
    }
  }

  public void sendIceCandidate(String participantId, String endpointName, IceCandidate candidate) {
    this.roomHandler.onIceCandidate(name, participantId, endpointName, candidate);
  }

  public void sendMediaError(String participantId, String description) {
    this.roomHandler.onMediaElementError(name, participantId, description);
  }

  public boolean isClosed() {
    return closed;
  }

  private void checkClosed() {
    if (closed) {
      throw new RoomException(Code.ROOM_CLOSED_ERROR_CODE, "The room '" + name + "' is closed");
    }
  }

  private void removeParticipant(Participant participant) {

    checkClosed();

    participants.remove(participant.getId());

    log.debug("ROOM {}: Cancel receiving media from user '{}' for other users", this.name,
        participant.getName());
    for (Participant other : participants.values()) {
      other.cancelReceivingMedia(participant.getName());
    }
  }

  public int getActivePublishers() {
    return activePublishers.get();
  }

  public void registerPublisher() {
    this.activePublishers.incrementAndGet();
  }

  public void deregisterPublisher() {
    this.activePublishers.decrementAndGet();
  }

  private void createPipeline() {
    synchronized (pipelineCreateLock) {
      if (pipeline != null) {
        return;
      }
      log.info("ROOM {}: Creating MediaPipeline", name);
      try {
        kurentoClient.createMediaPipeline(new Continuation<MediaPipeline>() {
          @Override
          public void onSuccess(MediaPipeline result) throws Exception {
            pipeline = result;
            pipelineLatch.countDown();
            log.debug("ROOM {}: Created MediaPipeline", name);
          }

          @Override
          public void onError(Throwable cause) throws Exception {
            pipelineLatch.countDown();
            log.error("ROOM {}: Failed to create MediaPipeline", name, cause);
          }
        });
      } catch (Exception e) {
        log.error("Unable to create media pipeline for room '{}'", name, e);
        pipelineLatch.countDown();
      }
      if (getPipeline() == null) {
        throw new RoomException(Code.ROOM_CANNOT_BE_CREATED_ERROR_CODE,
            "Unable to create media pipeline for room '" + name + "'");
      }

      pipeline.addErrorListener(new EventListener<ErrorEvent>() {
        @Override
        public void onEvent(ErrorEvent event) {
          String desc = event.getType() + ": " + event.getDescription() + "(errCode="
              + event.getErrorCode() + ")";
          log.warn("ROOM {}: Pipeline error encountered: {}", name, desc);
          roomHandler.onPipelineError(name, getParticipantIds(), desc);
        }
      });
    }
  }

  private void closePipeline() {
    synchronized (pipelineReleaseLock) {
      if (pipeline == null || pipelineReleased) {
        return;
      }
      getPipeline().release(new Continuation<Void>() {

        @Override
        public void onSuccess(Void result) throws Exception {
          log.debug("ROOM {}: Released Pipeline", Room.this.name);
          pipelineReleased = true;
        }

        @Override
        public void onError(Throwable cause) throws Exception {
          log.warn("ROOM {}: Could not successfully release Pipeline", Room.this.name, cause);
          pipelineReleased = true;
        }
      });
    }
  }

  public void recordSession(String participantId) {
      String RECORDING_EXT = ".webm";
      String TXT_EXTENSION = ".txt";
      SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-S");
      String RECORDING_PATH = "file:///var/lib/kurento/kurento-recordings/";
      MediaPipeline pipeline = getPipeline();
      Participant recordInitiator = getParticipant(participantId);
      Date date = new Date();
      String now = df.format(date);
      String timestamp = new Timestamp(date.getTime()).toString();
      String filePath = RECORDING_PATH + now + "/" + recordInitiator.getName() + RECORDING_EXT;

	  /* Working version, file save on server correct, has audio and video. But only from js client, android client has only audio. */
      Composite composite = recordInitiator.getComposite();
      MediaProfileSpecType profile = MediaProfileSpecType.WEBM;
      recorder = new RecorderEndpoint.Builder(pipeline, filePath).withMediaProfile(profile).build();
      HubPort recorderHubPort = new HubPort.Builder(composite).build();
      recorderHubPort.connect(recorder);
      recorder.record();
	  
	  /* Not working. File in server has 0 bytes
	  
	  WebRtcEndpoint endpoint = new WebRtcEndpoint.Builder(pipeline).build();
	  MediaProfileSpecType profile = MediaProfileSpecType.WEBM;
      recorder = new RecorderEndpoint.Builder(pipeline, filePath).withMediaProfile(profile).build();
	  endpoint.connect(recorder);
	  recorder.record();
	  */

      recordInitiator.setRecording(true);

      String fileName = recordInitiator.getRoom().getName() + "-" + recordInitiator.getName() + TXT_EXTENSION;
      String folderName = "kurento-recordings/" + now + "/";
      Path path = Paths.get(folderName);
      if (!Files.exists(path)) {
        try {
          Files.createDirectories(path);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
      String fileTXTPath = folderName + fileName;
      String textToWrite = timestamp + " " + "Start recording";

    log.debug("File PATH: {}", fileTXTPath);
      
      boolean success = writeToFile(fileTXTPath, textToWrite);
      if (!success) {
      	log.warn("Permission problem: can't write");
      } else {
        recordInitiator.setRecorderedFileName(fileTXTPath);
      }
  }

  public void stopRecording(String participantId) {
	recorder.stop();
	recorder.release();
	Participant participant = getParticipant(participantId);
	participant.setRecording(false);
	participant.setRecorderedFileName("");
  }

  public void saveCoord(String filePath, String coordinates, String windowSize, String brushColor, String brushSize,
		String brushType) {
	String newLine = System.getProperty("line.separator");
	Date date = new Date();
	String timestamp = new Timestamp(date.getTime()).toString();
	String stringParams = coordinates + ", " + windowSize + ", " + brushSize + 
    		", " + brushColor + ", " + brushType;
	String textToWrite = newLine + timestamp + " " + stringParams;
	log.debug("Add line to file: {}", textToWrite);
    boolean success = writeToFile(filePath, textToWrite);
    if (!success) {
    	log.warn("Permission problem: can't write");
    }
  }
  
  private boolean writeToFile(String filePath, String message) {
	  byte data[] = message.getBytes();
	  Path p = Paths.get(filePath);
      if (!Files.exists(p)){

      }
	  OutputStream outputStream = null;
	  try {
	    outputStream = new BufferedOutputStream(java.nio.file.Files.newOutputStream(p, CREATE, APPEND));
	    outputStream.write(data, 0, data.length);
	    outputStream.flush();
	    outputStream.close();
	    return true;
	  } catch (Exception e) {
	    e.printStackTrace();
	    return false;
	  }
  }
}
