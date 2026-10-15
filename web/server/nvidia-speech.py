"""NVIDIA hosted Parakeet RNNT, with real word timestamps (no estimated timing)."""
import json
import os
import sys
import wave

try:
    import grpc
    import riva.client
    from riva.client.proto.riva_asr_pb2 import RecognizeRequest
    auth = riva.client.Auth(use_ssl=True, uri='grpc.nvcf.nvidia.com:443', metadata_args=[
        ['function-id', os.environ['NVIDIA_FUNCTION_ID']],
        ['authorization', 'Bearer ' + os.environ['NVIDIA_API_KEY']]])
    service = riva.client.ASRService(auth)
    with wave.open(sys.argv[1], 'rb') as audio:
        samples = audio.readframes(audio.getnframes())
    config = riva.client.RecognitionConfig(
        encoding=riva.client.AudioEncoding.LINEAR_PCM, sample_rate_hertz=16000,
        language_code='multi', max_alternatives=1,
        enable_automatic_punctuation=True, enable_word_time_offsets=True)
    response = service.stub.Recognize(RecognizeRequest(config=config, audio=samples), metadata=auth.get_auth_metadata(), timeout=120)
    cues = []
    for result in response.results:
        if not result.alternatives:
            continue
        alternative = result.alternatives[0]
        words = alternative.words
        if alternative.transcript.strip() and not words:
            raise RuntimeError('NVIDIA returned text without word timestamps. Check model configuration.')
        group = []
        for word in words:
            group.append(word)
            if len(group) >= 8 or word.word.endswith(('.', '?', '!')):
                cues.append(dict(start=group[0].start_time / 1000, end=group[-1].end_time / 1000, text=' '.join(w.word for w in group)))
                group = []
        if group:
            cues.append(dict(start=group[0].start_time / 1000, end=group[-1].end_time / 1000, text=' '.join(w.word for w in group)))
    print(json.dumps({'cues': cues}))
except ImportError:
    print(json.dumps({'error': 'Install the NVIDIA runtime: npm run setup:speech', 'status': 400}))
    sys.exit(1)
except Exception as error:
    status = 400
    if hasattr(error, 'code'):
        status = {'RESOURCE_EXHAUSTED': 429, 'UNAVAILABLE': 503, 'DEADLINE_EXCEEDED': 504, 'UNAUTHENTICATED': 401, 'PERMISSION_DENIED': 403}.get(error.code().name, 500)
    print(json.dumps({'error': 'NVIDIA transcription failed (%s). Check your key, quota and model access.' % status, 'status': status}))
    sys.exit(1)
