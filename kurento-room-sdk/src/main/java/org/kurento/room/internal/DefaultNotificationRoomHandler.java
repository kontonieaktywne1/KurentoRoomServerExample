/*
 * (C) Copyright 2015 Kurento (http://kurento.org/)
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

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import static java.nio.file.StandardOpenOption.*;

import org.apache.commons.logging.Log;
import org.kurento.client.IceCandidate;
import org.kurento.client.RecorderEndpoint;
import org.kurento.room.NotificationRoomManager;
import org.kurento.room.api.NotificationRoomHandler;
import org.kurento.room.api.UserNotificationService;
import org.kurento.room.api.pojo.ParticipantRequest;
import org.kurento.room.api.pojo.UserParticipant;
import org.kurento.room.exception.RoomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.Files;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Default implementation that assumes that JSON-RPC messages specification was used for the
 * client-server communications.
 *
 * @author <a href="mailto:rvlad@naevatec.com">Radu Tom Vlad</a>
 */
public class DefaultNotificationRoomHandler implements NotificationRoomHandler {
	
  private final Logger log = LoggerFactory.getLogger(DefaultNotificationRoomHandler.class);

  private UserNotificationService notifService;

  public DefaultNotificationRoomHandler(UserNotificationService notifService) {
    this.notifService = notifService;
  }

  @Override
  public void onRoomClosed(String roomName, Set<UserParticipant> participants) {
    JsonObject notifParams = new JsonObject();
    notifParams.addProperty(ProtocolElements.ROOMCLOSED_ROOM_PARAM, roomName);
    for (UserParticipant participant : participants) {
      notifService.sendNotification(participant.getParticipantId(),
          ProtocolElements.ROOMCLOSED_METHOD, notifParams);
    }
  }

  @Override
  public void onParticipantJoined(ParticipantRequest request, String roomName, String newUserName,
      Set<UserParticipant> existingParticipants, RoomException error) {
    if (error != null) {
      notifService.sendErrorResponse(request, null, error);
      return;
    }

    JsonArray result = new JsonArray();
    for (UserParticipant participant : existingParticipants) {
      JsonObject participantJson = new JsonObject();
      participantJson
          .addProperty(ProtocolElements.JOINROOM_PEERID_PARAM, participant.getUserName());
      if (participant.isStreaming()) {
        JsonObject stream = new JsonObject();
        stream.addProperty(ProtocolElements.JOINROOM_PEERSTREAMID_PARAM, "webcam");
        JsonArray streamsArray = new JsonArray();
        streamsArray.add(stream);
        participantJson.add(ProtocolElements.JOINROOM_PEERSTREAMS_PARAM, streamsArray);
      }
      result.add(participantJson);

      JsonObject notifParams = new JsonObject();
      notifParams.addProperty(ProtocolElements.PARTICIPANTJOINED_USER_PARAM, newUserName);
      notifService.sendNotification(participant.getParticipantId(),
          ProtocolElements.PARTICIPANTJOINED_METHOD, notifParams);
    }
    notifService.sendResponse(request, result);
  }

  @Override
  public void onParticipantLeft(ParticipantRequest request, String userName,
      Set<UserParticipant> remainingParticipants, RoomException error) {
    if (error != null) {
      notifService.sendErrorResponse(request, null, error);
      return;
    }

    JsonObject params = new JsonObject();
    params.addProperty(ProtocolElements.PARTICIPANTLEFT_NAME_PARAM, userName);
    for (UserParticipant participant : remainingParticipants) {
      notifService.sendNotification(participant.getParticipantId(),
          ProtocolElements.PARTICIPANTLEFT_METHOD, params);
    }

    notifService.sendResponse(request, new JsonObject());
    notifService.closeSession(request);
  }

  @Override
  public void onPublishMedia(ParticipantRequest request, String publisherName, String sdpAnswer,
      Set<UserParticipant> participants, RoomException error) {
    if (error != null) {
      notifService.sendErrorResponse(request, null, error);
      return;
    }
    JsonObject result = new JsonObject();
    result.addProperty(ProtocolElements.PUBLISHVIDEO_SDPANSWER_PARAM, sdpAnswer);
    notifService.sendResponse(request, result);

    JsonObject params = new JsonObject();
    params.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_USER_PARAM, publisherName);
    JsonObject stream = new JsonObject();
    stream.addProperty(ProtocolElements.PARTICIPANTPUBLISHED_STREAMID_PARAM, "webcam");
    JsonArray streamsArray = new JsonArray();
    streamsArray.add(stream);
    params.add(ProtocolElements.PARTICIPANTPUBLISHED_STREAMS_PARAM, streamsArray);

    for (UserParticipant participant : participants) {
      if (participant.getParticipantId().equals(request.getParticipantId())) {
        continue;
      } else {
        notifService.sendNotification(participant.getParticipantId(),
            ProtocolElements.PARTICIPANTPUBLISHED_METHOD, params);
      }
    }
  }

  @Override
  public void onUnpublishMedia(ParticipantRequest request, String publisherName,
      Set<UserParticipant> participants, RoomException error) {
    if (error != null) {
      notifService.sendErrorResponse(request, null, error);
      return;
    }
    notifService.sendResponse(request, new JsonObject());

    JsonObject params = new JsonObject();
    params.addProperty(ProtocolElements.PARTICIPANTUNPUBLISHED_NAME_PARAM, publisherName);

    for (UserParticipant participant : participants) {
      if (participant.getParticipantId().equals(request.getParticipantId())) {
        continue;
      } else {
        notifService.sendNotification(participant.getParticipantId(),
            ProtocolElements.PARTICIPANTUNPUBLISHED_METHOD, params);
      }
    }
  }

  @Override
  public void onSubscribe(ParticipantRequest request, String sdpAnswer, RoomException error) {
    if (error != null) {
      notifService.sendErrorResponse(request, null, error);
      return;
    }
    JsonObject result = new JsonObject();
    result.addProperty(ProtocolElements.RECEIVEVIDEO_SDPANSWER_PARAM, sdpAnswer);
    notifService.sendResponse(request, result);
  }

  @Override
  public void onUnsubscribe(ParticipantRequest request, RoomException error) {
    if (error != null) {
      notifService.sendErrorResponse(request, null, error);
      return;
    }
    notifService.sendResponse(request, new JsonObject());
  }

  @Override
  public void onSendMessage(ParticipantRequest request, String message, String userName,
      String roomName, Set<UserParticipant> participants, RoomException error) {
    if (error != null) {
      notifService.sendErrorResponse(request, null, error);
      return;
    }
    notifService.sendResponse(request, new JsonObject());

    JsonObject params = new JsonObject();
    params.addProperty(ProtocolElements.PARTICIPANTSENDMESSAGE_ROOM_PARAM, roomName);
    params.addProperty(ProtocolElements.PARTICIPANTSENDMESSAGE_USER_PARAM, userName);
    params.addProperty(ProtocolElements.PARTICIPANTSENDMESSAGE_MESSAGE_PARAM, message);

    for (UserParticipant participant : participants) {
      notifService.sendNotification(participant.getParticipantId(),
          ProtocolElements.PARTICIPANTSENDMESSAGE_METHOD, params);
    }
  }
  
  @Override
  public void onPaintSend(ParticipantRequest request, String userName, String coordinates, String windowSize,
  		String brushColor, String brushSize, String brushType, String roomName, Set<UserParticipant> participants,
  		RoomException error) {
  	if (error != null) {
      notifService.sendErrorResponse(request, null, error);
      return;
  	}
  	
  	notifService.sendResponse(request, new JsonObject());

    JsonObject params = new JsonObject();
    params.addProperty(ProtocolElements.ONPAINTSEND_ROOM_PARAM, roomName);
    params.addProperty(ProtocolElements.ONPAINTSEND_USER_PARAM, userName);
    params.addProperty(ProtocolElements.ONPAINTSEND_COORDS_PARAM, coordinates);
    params.addProperty(ProtocolElements.ONPAINTSEND_DISPLAY_SIZE_PARAM, windowSize);
    params.addProperty(ProtocolElements.ONPAINTSEND_BRUSH_SIZE_PARAM, brushSize);
    params.addProperty(ProtocolElements.ONPAINTSEND_BRUSH_COLOR_PARAM, brushColor);
    params.addProperty(ProtocolElements.ONPAINTSEND_BRUSH_TYPE_PARAM, brushType);
    
    /*Date date = new Date();
    String newLine = System.getProperty("line.separator");
    String timestamp = new Timestamp(date.getTime()).toString();
    String stringParams = coordinates + ", " + windowSize + ", " + brushSize + 
    		", " + brushColor + ", " + brushType;
    String TXT_EXTENSION = ".txt";
    //TODO change path
    String PATH = "file:///kurento-recordings/";
    String fileName = roomName + "-" + userName + TXT_EXTENSION;
    String filePath = PATH + fileName;
    String textToWrite = newLine + timestamp + " " + stringParams;
    byte data[] = textToWrite.getBytes(); */
    
    
    
    /*File file = new File(PATH, fileName);
    PrintWriter printWriter = null;
    OutputStreamWriter streamWriter = null;
    FileOutputStream fileOutputStream = null;
    String absolutePath = file.getAbsolutePath();*/
    
    /*log.debug("File PATH: {}", filePath);
    
    Path p = Paths.get(filePath);
    OutputStream outputStream = null;
    try {
    	outputStream = new BufferedOutputStream(java.nio.file.Files.newOutputStream(p, CREATE, APPEND));
    	outputStream.write(data, 0, data.length);
    	outputStream.flush();
    	outputStream.close();
    } catch (Exception e) {
    	e.printStackTrace();
    }
    if (file.getParentFile() != null && !file.getParentFile().mkdirs()) {
    	log.warn("Perrmission problem: can't write");
    }
  
    try {
    	if (!file.exists()) {
    		file.createNewFile();
    	}
    	fileOutputStream = new FileOutputStream(file, true);
    	streamWriter = new OutputStreamWriter(fileOutputStream, "UTF-8");
    	printWriter = new PrintWriter(streamWriter);
    	printWriter.write(newLine + timestamp + " " + stringParams);
    } catch (IOException e) {
    	e.printStackTrace();
    } finally {
    	try {
    		if (printWriter != null) {
    			printWriter.flush();
    			printWriter.close();
			}
			if (streamWriter != null) {
				streamWriter.flush();
				streamWriter.close();
			}
			if (fileOutputStream != null) {
				fileOutputStream.flush();
				fileOutputStream.close();
			}
    	} catch (IOException e) {
			e.printStackTrace();
		}
	}*/

    for (UserParticipant participant : participants) {
      notifService.sendNotification(participant.getParticipantId(),
              ProtocolElements.ONPAINTSEND_METHOD, params);
    }
  }
  
  @Override
  public void onStopSessionRecording(ParticipantRequest request, String error) {
	if (error != null) {
      //notifService.sendErrorResponse(request, "Error", null);
	} else {
      notifService.sendResponse(request, new JsonObject());
    }
  }

  @Override
  public void onSessionRecording(ParticipantRequest request, String error) {
	if (error != null) {
      //notifService.sendErrorResponse(request, "Error", null);
	} else {
      notifService.sendResponse(request, new JsonObject());
    }
  }

  @Override
  public void onRecvIceCandidate(ParticipantRequest request, RoomException error) {
    if (error != null) {
      notifService.sendErrorResponse(request, null, error);
      return;
    }

    notifService.sendResponse(request, new JsonObject());
  }

  @Override
  public void onParticipantLeft(String userName, Set<UserParticipant> remainingParticipants) {
    JsonObject params = new JsonObject();
    params.addProperty(ProtocolElements.PARTICIPANTLEFT_NAME_PARAM, userName);
    for (UserParticipant participant : remainingParticipants) {
      notifService.sendNotification(participant.getParticipantId(),
          ProtocolElements.PARTICIPANTLEFT_METHOD, params);
    }
  }

  @Override
  public void onParticipantEvicted(UserParticipant participant) {
    notifService.sendNotification(participant.getParticipantId(),
        ProtocolElements.PARTICIPANTEVICTED_METHOD, new JsonObject());
  }

  // ------------ EVENTS FROM ROOM HANDLER -----

  @Override
  public void onIceCandidate(String roomName, String participantId, String endpointName,
      IceCandidate candidate) {
    JsonObject params = new JsonObject();
    params.addProperty(ProtocolElements.ICECANDIDATE_EPNAME_PARAM, endpointName);
    params.addProperty(ProtocolElements.ICECANDIDATE_SDPMLINEINDEX_PARAM,
        candidate.getSdpMLineIndex());
    params.addProperty(ProtocolElements.ICECANDIDATE_SDPMID_PARAM, candidate.getSdpMid());
    params.addProperty(ProtocolElements.ICECANDIDATE_CANDIDATE_PARAM, candidate.getCandidate());
    notifService.sendNotification(participantId, ProtocolElements.ICECANDIDATE_METHOD, params);
  }

  @Override
  public void onPipelineError(String roomName, Set<String> participantIds, String description) {
    JsonObject notifParams = new JsonObject();
    notifParams.addProperty(ProtocolElements.MEDIAERROR_ERROR_PARAM, description);
    for (String pid : participantIds) {
      notifService.sendNotification(pid, ProtocolElements.MEDIAERROR_METHOD, notifParams);
    }
  }

  @Override
  public void onMediaElementError(String roomName, String participantId, String description) {
    JsonObject notifParams = new JsonObject();
    notifParams.addProperty(ProtocolElements.MEDIAERROR_ERROR_PARAM, description);
    notifService.sendNotification(participantId, ProtocolElements.MEDIAERROR_METHOD, notifParams);
  }
}
