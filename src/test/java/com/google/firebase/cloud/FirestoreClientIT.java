package com.google.firebase.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.common.collect.ImmutableMap;
import com.google.firebase.testing.IntegrationTestUtils;
import org.junit.Test;

import java.util.Map;

public class FirestoreClientIT {

  @Test
  public void testFirestore() throws Exception {
    Firestore firestore = FirestoreClient.getInstance(IntegrationTestUtils.ensureDefaultApp())
        .firestore();
    assertNotNull(firestore);
    DocumentReference doc = firestore.collection("cities").document("LA ");
    Map<String, String> data = ImmutableMap.of("key", "value");
    doc.set(data).get();
    DocumentSnapshot snapshot = doc.get().get();
    assertEquals("value", snapshot.getString("key"));
  }

}
