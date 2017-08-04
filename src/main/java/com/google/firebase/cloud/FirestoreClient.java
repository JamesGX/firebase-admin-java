package com.google.firebase.cloud;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.firebase.FirebaseApp;
import com.google.firebase.ImplFirebaseTrampolines;
import com.google.firebase.auth.FirebaseCredentials;
import com.google.firebase.internal.FirebaseCloudCredentials;
import com.google.firebase.internal.FirebaseService;

public class FirestoreClient {

  private final FirebaseApp app;
  private final Firestore firestore;

  private FirestoreClient(FirebaseApp app) {
    this.app = checkNotNull(app, "FirebaseApp must not be null");
    this.firestore = FirestoreOptions.newBuilder()
        .setCredentials(new FirebaseCloudCredentials(app))
        .setProjectId(FirebaseCredentials.getProjectId(app))
        .build()
        .getService();
  }

  public static FirestoreClient getInstance() {
    return getInstance(FirebaseApp.getInstance());
  }

  public static FirestoreClient getInstance(FirebaseApp app) {
    FirestoreClientService service = ImplFirebaseTrampolines.getService(
        app, SERVICE_ID, FirestoreClientService.class);
    if (service == null) {
      service = ImplFirebaseTrampolines.addService(app, new FirestoreClientService(app));
    }
    return service.getInstance();
  }

  public Firestore firestore() {
    return firestore;
  }

  private static final String SERVICE_ID = FirestoreClient.class.getName();

  private static class FirestoreClientService extends FirebaseService<FirestoreClient> {

    FirestoreClientService(FirebaseApp app) {
      super(SERVICE_ID, new FirestoreClient(app));
    }

    @Override
    public void destroy() {
      // NOTE: We don't explicitly tear down anything here, but public methods of Firestore
      // will now fail because calls to getOptions() and getToken() will hit FirebaseApp,
      // which will throw once the app is deleted.
    }
  }
}
