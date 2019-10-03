package com.google.cloud.hadoop.gcsio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.cloud.hadoop.gcsio.GoogleCloudStorageReadOptions.Fadvise;
import com.google.google.storage.v1.ChecksummedData;
import com.google.google.storage.v1.GetObjectMediaRequest;
import com.google.google.storage.v1.GetObjectMediaResponse;
import com.google.google.storage.v1.GetObjectRequest;
import com.google.google.storage.v1.Object;
import com.google.google.storage.v1.StorageGrpc;
import com.google.google.storage.v1.StorageGrpc.StorageImplBase;
import com.google.google.storage.v1.StorageGrpc.StorageStub;
import com.google.protobuf.ByteString;
import com.google.protobuf.UInt32Value;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class GoogleCloudStorageGrpcReadChannelTest {
  private static final String BUCKET_NAME = "bucket-name";
  private static final String OBJECT_NAME = "object-name";
  private static final int OBJECT_SIZE = 640;
  private static final int DEFAULT_OBJECT_CRC32C = 185327488;
  private static Object DEFAULT_OBJECT =
      Object.newBuilder()
          .setBucket(BUCKET_NAME)
          .setName(OBJECT_NAME)
          .setSize(OBJECT_SIZE)
          .setCrc32C(UInt32Value.newBuilder().setValue(DEFAULT_OBJECT_CRC32C))
          .setGeneration(1)
          .build();
  private static GetObjectRequest GET_OBJECT_REQUEST =
      GetObjectRequest.newBuilder().setBucket(BUCKET_NAME).setObject(OBJECT_NAME).build();
  private static GetObjectMediaRequest GET_OBJECT_MEDIA_REQUEST =
      GetObjectMediaRequest.newBuilder()
          .setBucket(BUCKET_NAME)
          .setObject(OBJECT_NAME)
          .setGeneration(1)
          .build();

  private StorageStub stub;
  private FakeService fakeService;
  private ExecutorService executor = Executors.newCachedThreadPool();

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  @Before
  public void setUp() throws Exception {
    fakeService = spy(new FakeService());
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(fakeService)
            .build()
            .start());
    stub =
        StorageGrpc.newStub(
            grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build()));
  }

  @Test
  public void readSingleChunkSucceeds() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).build());

    ByteBuffer buffer = ByteBuffer.allocate(100);
    readChannel.read(buffer);

    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
    verify(fakeService, times(1)).getObjectMedia(eq(GET_OBJECT_MEDIA_REQUEST), any());
    assertArrayEquals(fakeService.data.substring(0, 100).toByteArray(), buffer.array());
  }

  @Test
  public void readMultipleChunksSucceeds() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();
    // Enough to require multiple chunks.
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(5120).build());

    ByteBuffer buffer = ByteBuffer.allocate(5120);
    readChannel.read(buffer);

    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
    verify(fakeService, times(1)).getObjectMedia(eq(GET_OBJECT_MEDIA_REQUEST), any());
    assertArrayEquals(fakeService.data.substring(0, 5120).toByteArray(), buffer.array());
  }

  @Test
  public void multipleSequentialReads() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).build());

    ByteBuffer first_buffer = ByteBuffer.allocate(10);
    ByteBuffer second_buffer = ByteBuffer.allocate(20);
    readChannel.read(first_buffer);
    readChannel.read(second_buffer);

    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
    verify(fakeService, times(1)).getObjectMedia(eq(GET_OBJECT_MEDIA_REQUEST), any());
    assertArrayEquals(fakeService.data.substring(0, 10).toByteArray(), first_buffer.array());
    assertArrayEquals(fakeService.data.substring(10, 30).toByteArray(), second_buffer.array());
  }

  @Test
  public void readToBufferWithArrayOffset() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).build());

    byte[] array = new byte[200];
    // `slice` generates a ByteBuffer with a non-zero `arrayOffset`.
    ByteBuffer buffer = ByteBuffer.wrap(array, 50, 150).slice();
    readChannel.read(buffer);

    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
    verify(fakeService, times(1)).getObjectMedia(eq(GET_OBJECT_MEDIA_REQUEST), any());
    byte[] expected = ByteString.copyFrom(array, 50, 100).toByteArray();
    assertArrayEquals(fakeService.data.substring(0, 100).toByteArray(), expected);
  }

  @Test
  public void readSucceedsAfterSeek() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).build());

    ByteBuffer buffer = ByteBuffer.allocate(10);
    readChannel.position(50);
    readChannel.read(buffer);

    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
    verify(fakeService, times(1))
        .getObjectMedia(eq(GET_OBJECT_MEDIA_REQUEST.toBuilder().setReadOffset(50).build()), any());
    assertArrayEquals(fakeService.data.substring(50, 60).toByteArray(), buffer.array());
  }

  @Test
  public void singleReadSucceedsWithValidObjectChecksum() throws Exception {
    fakeService.setObject(
        DEFAULT_OBJECT.toBuilder()
            .setCrc32C(UInt32Value.newBuilder().setValue(DEFAULT_OBJECT_CRC32C))
            .build());
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    ByteBuffer buffer = ByteBuffer.allocate(OBJECT_SIZE);
    readChannel.read(buffer);

    assertArrayEquals(fakeService.data.toByteArray(), buffer.array());
  }

  @Test
  public void singleReadFailsWithInvalidObjectChecksum() throws Exception {
    fakeService.setObject(
        DEFAULT_OBJECT.toBuilder().setCrc32C(UInt32Value.newBuilder().setValue(0)).build());
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    ByteBuffer buffer = ByteBuffer.allocate(OBJECT_SIZE);
    IOException thrown = assertThrows(IOException.class, () -> readChannel.read(buffer));
    assertTrue(thrown.getMessage().contains("Object checksum"));
  }

  @Test
  public void singleReadFailsWithNoObjectChecksum() throws Exception {
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().clearCrc32C().build());
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    ByteBuffer buffer = ByteBuffer.allocate(OBJECT_SIZE);
    IOException thrown = assertThrows(IOException.class, () -> readChannel.read(buffer));
    assertTrue(thrown.getMessage().contains("Object checksum"));
  }

  @Test
  public void partialReadSucceedsWithInvalidObjectChecksum() throws Exception {
    fakeService.setObject(
        DEFAULT_OBJECT.toBuilder().setCrc32C(UInt32Value.newBuilder().setValue(0)).build());
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    ByteBuffer buffer = ByteBuffer.allocate(OBJECT_SIZE - 10);
    readChannel.read(buffer);

    assertArrayEquals(
        fakeService.data.substring(0, OBJECT_SIZE - 10).toByteArray(), buffer.array());
  }

  @Test
  public void multipleSequentialsReadsSucceedWithValidObjectChecksum() throws Exception {
    fakeService.setObject(
        DEFAULT_OBJECT.toBuilder()
            .setCrc32C(UInt32Value.newBuilder().setValue(DEFAULT_OBJECT_CRC32C))
            .build());
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    ByteBuffer firstBuffer = ByteBuffer.allocate(100);
    ByteBuffer secondBuffer = ByteBuffer.allocate(OBJECT_SIZE - 100);
    readChannel.read(firstBuffer);
    readChannel.read(secondBuffer);

    assertArrayEquals(fakeService.data.substring(0, 100).toByteArray(), firstBuffer.array());
    assertArrayEquals(fakeService.data.substring(100).toByteArray(), secondBuffer.array());
  }

  @Test
  public void multipleSequentialsReadsFailWithInvalidObjectChecksum() throws Exception {
    fakeService.setObject(
        DEFAULT_OBJECT.toBuilder().setCrc32C(UInt32Value.newBuilder().setValue(0)).build());
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    ByteBuffer firstBuffer = ByteBuffer.allocate(100);
    ByteBuffer secondBuffer = ByteBuffer.allocate(OBJECT_SIZE - 100);
    readChannel.read(firstBuffer);

    IOException thrown = assertThrows(IOException.class, () -> readChannel.read(secondBuffer));
    assertTrue(thrown.getMessage().contains("Object checksum"));
  }

  @Test
  public void readToBufferWithArrayOffsetSucceedsWithValidObjectChecksum() throws Exception {
    fakeService.setObject(
        DEFAULT_OBJECT.toBuilder()
            .setCrc32C(UInt32Value.newBuilder().setValue(DEFAULT_OBJECT_CRC32C))
            .build());
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    byte[] array = new byte[OBJECT_SIZE + 100];
    // `ByteBuffer.slice` generates a ByteBuffer with a non-zero `arrayOffset`.
    ByteBuffer buffer = ByteBuffer.wrap(array, 50, OBJECT_SIZE).slice();
    readChannel.read(buffer);

    byte[] expected = ByteString.copyFrom(array, 50, OBJECT_SIZE).toByteArray();
    assertArrayEquals(fakeService.data.toByteArray(), expected);
  }

  @Test
  public void readToBufferWithArrayOffsetFailsWithInvalidObjectChecksum() throws Exception {
    fakeService.setObject(
        DEFAULT_OBJECT.toBuilder().setCrc32C(UInt32Value.newBuilder().setValue(0)).build());
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setGrpcChecksumsEnabled(true).build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    byte[] array = new byte[OBJECT_SIZE + 100];
    // `ByteBuffer.slice` generates a ByteBuffer with a non-zero `arrayOffset`.
    ByteBuffer buffer = ByteBuffer.wrap(array, 50, OBJECT_SIZE).slice();

    IOException thrown = assertThrows(IOException.class, () -> readChannel.read(buffer));
    assertTrue(thrown.getMessage().contains("Object checksum"));
  }

  @Test
  public void readIgnoresObjectChecksumAfterSeekInFadviseAuto() throws Exception {
    fakeService.setObject(
        DEFAULT_OBJECT.toBuilder().setCrc32C(UInt32Value.newBuilder().setValue(0)).build());
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder()
            .setGrpcChecksumsEnabled(true)
            .setFadvise(Fadvise.AUTO)
            .build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    ByteBuffer firstBuffer = ByteBuffer.allocate(99);
    ByteBuffer secondBuffer = ByteBuffer.allocate(OBJECT_SIZE - 100);
    readChannel.read(firstBuffer);
    readChannel.position(100);
    readChannel.read(secondBuffer);

    assertArrayEquals(fakeService.data.substring(0, 99).toByteArray(), firstBuffer.array());
    assertArrayEquals(fakeService.data.substring(100).toByteArray(), secondBuffer.array());
  }

  @Test
  public void readHandlesGetError() throws Exception {
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(false).build();
    fakeService.setGetException(
        Status.fromCode(Status.Code.INTERNAL)
            .withDescription("Custom error message.")
            .asException());
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    ByteBuffer buffer = ByteBuffer.allocate(10);
    IOException thrown = assertThrows(IOException.class, () -> readChannel.read(buffer));
    assertTrue(thrown.getCause().getMessage().contains("Custom error message."));
  }

  @Test
  public void readHandlesGetMediaError() throws Exception {
    fakeService.setGetMediaException(
        Status.fromCode(Status.Code.INTERNAL)
            .withDescription("Custom error message.")
            .asException());
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();

    ByteBuffer buffer = ByteBuffer.allocate(10);
    IOException thrown = assertThrows(IOException.class, () -> readChannel.read(buffer));
    assertTrue(thrown.getCause().getMessage().contains("Custom error message."));
  }

  @Test
  public void readFailsOnClosedChannel() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();

    readChannel.close();
    ByteBuffer buffer = ByteBuffer.allocate(10);
    assertThrows(ClosedChannelException.class, () -> readChannel.read(buffer));
  }

  @Test
  public void readSucceeds() throws Exception {
    GoogleCloudStorageReadOptions options = GoogleCloudStorageReadOptions.builder().build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).setGeneration(1).build());
    ByteBuffer buffer = ByteBuffer.allocate(10);
    readChannel.read(buffer);
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).setGeneration(2).build());
    readChannel.position(0);
    buffer.clear();
    readChannel.read(buffer);

    List<GetObjectMediaRequest> expectedRequests =
        Arrays.asList(
            GET_OBJECT_MEDIA_REQUEST,
            GET_OBJECT_MEDIA_REQUEST.toBuilder().setGeneration(1).build());
    ArgumentCaptor<GetObjectMediaRequest> requestCaptor =
        ArgumentCaptor.forClass(GetObjectMediaRequest.class);
    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
    verify(fakeService, times(2)).getObjectMedia(requestCaptor.capture(), any());
    assertEquals(expectedRequests, requestCaptor.getAllValues());
  }

  @Test
  public void seekUnderInplaceSeekLimitInFadviseAutoReadsSequentially() throws Exception {
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder()
            .setFadvise(Fadvise.AUTO)
            .setInplaceSeekLimit(10)
            .build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).build());

    ByteBuffer buffer = ByteBuffer.allocate(20);
    readChannel.read(buffer);
    readChannel.position(25);
    buffer.clear();
    readChannel.read(buffer);

    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
    verify(fakeService, times(1)).getObjectMedia(eq(GET_OBJECT_MEDIA_REQUEST), any());
    assertArrayEquals(fakeService.data.substring(25, 45).toByteArray(), buffer.array());
  }

  @Test
  public void seekBackwardsInFadviseAutoTriggersRandomAccessReads() throws Exception {
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFadvise(Fadvise.AUTO).build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).build());

    ByteBuffer buffer = ByteBuffer.allocate(20);
    readChannel.read(buffer);
    readChannel.position(10);
    buffer.clear();
    readChannel.read(buffer);

    List<GetObjectMediaRequest> expectedRequests =
        Arrays.asList(
            GET_OBJECT_MEDIA_REQUEST,
            GET_OBJECT_MEDIA_REQUEST.toBuilder().setReadOffset(10).setReadLimit(20).build());
    ArgumentCaptor<GetObjectMediaRequest> requestCaptor =
        ArgumentCaptor.forClass(GetObjectMediaRequest.class);
    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
    verify(fakeService, times(2)).getObjectMedia(requestCaptor.capture(), any());
    assertEquals(expectedRequests, requestCaptor.getAllValues());
    assertArrayEquals(fakeService.data.substring(10, 30).toByteArray(), buffer.array());
  }

  @Test
  public void seekPastInplaceSeekLimitInFadviseAutoTriggersRandomAccessReads() throws Exception {
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder()
            .setFadvise(Fadvise.AUTO)
            .setInplaceSeekLimit(10)
            .build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).build());

    ByteBuffer buffer = ByteBuffer.allocate(10);
    readChannel.read(buffer);
    buffer.clear();
    readChannel.position(30);
    readChannel.read(buffer);

    List<GetObjectMediaRequest> expectedRequests =
        Arrays.asList(
            GET_OBJECT_MEDIA_REQUEST,
            GET_OBJECT_MEDIA_REQUEST.toBuilder().setReadOffset(30).setReadLimit(10).build());
    ArgumentCaptor<GetObjectMediaRequest> requestCaptor =
        ArgumentCaptor.forClass(GetObjectMediaRequest.class);
    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
    verify(fakeService, times(2)).getObjectMedia(requestCaptor.capture(), any());
    assertEquals(expectedRequests, requestCaptor.getAllValues());
    assertArrayEquals(fakeService.data.substring(30, 40).toByteArray(), buffer.array());
  }

  @Test
  public void seekReadsSequentiallyInSequentialMode() throws Exception {
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFadvise(Fadvise.SEQUENTIAL).build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).build());

    ByteBuffer buffer = ByteBuffer.allocate(10);
    readChannel.read(buffer);
    buffer.clear();
    readChannel.position(30);
    readChannel.read(buffer);

    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
    verify(fakeService, times(1)).getObjectMedia(eq(GET_OBJECT_MEDIA_REQUEST), any());
    assertArrayEquals(fakeService.data.substring(30, 40).toByteArray(), buffer.array());
  }

  @Test
  public void seekReadsRandomlyInRandomMode() throws Exception {
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFadvise(Fadvise.RANDOM).build();
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).build());

    ByteBuffer buffer = ByteBuffer.allocate(10);
    readChannel.read(buffer);
    buffer.clear();
    readChannel.read(buffer);

    List<GetObjectMediaRequest> expectedRequests =
        Arrays.asList(
            GET_OBJECT_MEDIA_REQUEST.toBuilder().setReadLimit(10).build(),
            GET_OBJECT_MEDIA_REQUEST.toBuilder().setReadOffset(10).setReadLimit(10).build());
    ArgumentCaptor<GetObjectMediaRequest> requestCaptor =
        ArgumentCaptor.forClass(GetObjectMediaRequest.class);
    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
    verify(fakeService, times(2)).getObjectMedia(requestCaptor.capture(), any());
    assertEquals(expectedRequests, requestCaptor.getAllValues());
    assertArrayEquals(fakeService.data.substring(10, 20).toByteArray(), buffer.array());
  }

  @Test
  public void seekFailsOnNegative() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();

    assertThrows(IllegalArgumentException.class, () -> readChannel.position(-1));
  }

  @Test
  public void seekFailsOnClosedChannel() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();

    readChannel.close();
    assertThrows(ClosedChannelException.class, () -> readChannel.position(2));
  }

  @Test
  public void positionUpdatesOnRead() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(100).build());

    ByteBuffer buffer = ByteBuffer.allocate(50);
    readChannel.read(buffer);

    assertEquals(50, readChannel.position());
  }

  @Test
  public void positionUpdatesOnSeek() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();

    readChannel.position(50);

    assertEquals(50, readChannel.position());
  }

  @Test
  public void positionFailsOnClosedChannel() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();

    readChannel.close();
    assertThrows(ClosedChannelException.class, readChannel::position);
  }

  @Test
  public void fastFailOnNotFoundFailsOnCreateWhenEnabled() throws Exception {
    fakeService.setGetException(Status.NOT_FOUND.asException());
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(true).build();

    assertThrows(FileNotFoundException.class, () -> newReadChannel(options));
  }

  @Test
  public void fastFailOnNotFoundFailsOnReadWhenDisabled() throws Exception {
    fakeService.setGetException(Status.NOT_FOUND.asException());
    GoogleCloudStorageReadOptions options =
        GoogleCloudStorageReadOptions.builder().setFastFailOnNotFound(false).build();

    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel(options);

    ByteBuffer buffer = ByteBuffer.allocate(10);
    assertThrows(FileNotFoundException.class, () -> readChannel.read(buffer));
  }

  @Test
  public void sizeReturnsObjectSize() throws Exception {
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(1234).build());
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();

    assertEquals(1234L, readChannel.size());
    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
  }

  @Test
  public void sizeFailsOnClosedChannel() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();

    readChannel.close();
    assertThrows(ClosedChannelException.class, readChannel::size);
  }

  @Test
  public void sizeIsCached() throws Exception {
    fakeService.setObject(DEFAULT_OBJECT.toBuilder().setSize(1234).build());
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();

    assertEquals(1234L, readChannel.size());
    assertEquals(1234L, readChannel.size());
    verify(fakeService, times(1)).getObject(eq(GET_OBJECT_REQUEST), any());
  }

  @Test
  public void isOpenReturnsTrueOnCreate() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();

    assertTrue(readChannel.isOpen());
  }

  @Test
  public void isOpenReturnsFalseAfterClose() throws Exception {
    GoogleCloudStorageGrpcReadChannel readChannel = newReadChannel();

    readChannel.close();
    assertFalse(readChannel.isOpen());
  }

  private GoogleCloudStorageGrpcReadChannel newReadChannel(GoogleCloudStorageReadOptions options)
      throws IOException {
    return new GoogleCloudStorageGrpcReadChannel(stub, BUCKET_NAME, OBJECT_NAME, options);
  }

  private GoogleCloudStorageGrpcReadChannel newReadChannel() throws IOException {
    return newReadChannel(GoogleCloudStorageReadOptions.DEFAULT);
  }

  private static class FakeService extends StorageImplBase {
    private static final int CHUNK_SIZE = 2048;
    private Object object;
    private Throwable getException;
    private Throwable getMediaException;
    ByteString data;

    public FakeService() {
      setObject(DEFAULT_OBJECT);
    }

    @Override
    public void getObject(GetObjectRequest request, StreamObserver<Object> responseObserver) {
      if (getException != null) {
        responseObserver.onError(getException);
      } else {
        responseObserver.onNext(object);
        responseObserver.onCompleted();
      }
    }

    @Override
    public void getObjectMedia(
        GetObjectMediaRequest request, StreamObserver<GetObjectMediaResponse> responseObserver) {
      if (getMediaException != null) {
        responseObserver.onError(getMediaException);
      } else {
        int readStart = (int) request.getReadOffset();
        int readEnd =
            request.getReadLimit() > 0
                ? (int) Math.min(object.getSize(), readStart + request.getReadLimit())
                : (int) object.getSize();
        for (int position = readStart; position < readEnd; position += CHUNK_SIZE) {
          GetObjectMediaResponse response =
              GetObjectMediaResponse.newBuilder()
                  .setChecksummedData(
                      ChecksummedData.newBuilder()
                          .setContent(
                              data.substring(
                                  position,
                                  Math.min((int) object.getSize(), position + CHUNK_SIZE))))
                  .build();
          responseObserver.onNext(response);
        }
        responseObserver.onCompleted();
      }
    }

    public void setObject(Object object) {
      this.object = object;
      data = createTestData((int) object.getSize());
    }

    void setGetException(Throwable t) {
      getException = t;
    }

    void setGetMediaException(Throwable t) {
      getMediaException = t;
    }

    private static ByteString createTestData(int numBytes) {
      byte[] result = new byte[numBytes];
      for (int i = 0; i < numBytes; ++i) {
        result[i] = (byte) i;
      }

      return ByteString.copyFrom(result);
    }
  }
}