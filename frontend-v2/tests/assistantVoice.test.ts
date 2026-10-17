import { createApp, h } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAssistantVoice } from '../src/composables/useAssistantVoice'

const { startRecordingMock, assistantTranscribeMock } = vi.hoisted(() => ({
  startRecordingMock: vi.fn(),
  assistantTranscribeMock: vi.fn(),
}))

vi.mock('../src/utils/asr', () => ({
  asrLanguageFor: (locale: string) => locale,
  initializeAsr: vi.fn().mockResolvedValue(undefined),
  serverAsrReady: () => true,
  startRecording: startRecordingMock,
}))
vi.mock('../src/api/speech', () => ({ assistantTranscribe: assistantTranscribeMock }))
vi.mock('../src/utils/tts', async () => {
  const { ref } = await import('vue')
  return {
    initializeTts: vi.fn().mockResolvedValue(undefined),
    speakingKey: ref(''),
    stripHtml: (value: string) => value,
    ttsRuntimeConfig: ref({ provider: 'browser' }),
    ttsSpeak: vi.fn(),
    ttsStop: vi.fn(),
    ttsSupported: () => true,
  }
})
vi.mock('../src/composables/useAssistantPreferences', () => ({
  useAssistantPreferences: () => ({
    voiceReply: { value: false },
    voiceInput: { value: true },
    setVoiceReply: vi.fn(),
  }),
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function withSetup<T>(composable: () => T): T {
  let result!: T
  const app = createApp({ setup() { result = composable(); return () => h('div') } })
  app.mount(document.createElement('div'))
  return result
}

function session(overrides: Partial<{ stop: () => Promise<Blob>; cancel: () => void }> = {}) {
  return {
    stop: overrides.stop || vi.fn().mockResolvedValue(new Blob(['audio'])),
    cancel: overrides.cancel || vi.fn(),
  }
}

describe('DF Agent voice cancellation fences', () => {
  beforeEach(() => {
    vi.resetModules()
    startRecordingMock.mockReset()
    assistantTranscribeMock.mockReset()
  })

  afterEach(() => vi.restoreAllMocks())

  it('cancels a microphone request that resolves after the user cancels', async () => {
    const pending = deferred<ReturnType<typeof session>>()
    startRecordingMock.mockReturnValue(pending.promise)
    const onFinal = vi.fn()
    const resolved = withSetup(() => useAssistantVoice())

    resolved.startListening('zh-CN', onFinal)
    expect(resolved.listening.value).toBe(true)
    resolved.cancelListening()
    expect(resolved.listening.value).toBe(false)

    const late = session()
    pending.resolve(late)
    await Promise.resolve()
    await Promise.resolve()
    expect(late.cancel).toHaveBeenCalledOnce()
    expect(onFinal).not.toHaveBeenCalled()
  })

  it('treats stop as cancellation while microphone permission is pending', async () => {
    const pending = deferred<ReturnType<typeof session>>()
    startRecordingMock.mockReturnValue(pending.promise)
    const onFinal = vi.fn()
    const resolved = withSetup(() => useAssistantVoice())

    resolved.startListening('en', onFinal)
    expect(resolved.listening.value).toBe(true)
    resolved.stopListening()
    expect(resolved.listening.value).toBe(false)

    const late = session()
    pending.resolve(late)
    await Promise.resolve()
    await Promise.resolve()
    expect(late.cancel).toHaveBeenCalledOnce()
    expect(onFinal).not.toHaveBeenCalled()
  })

  it('does not open a second microphone request while authorization is pending', async () => {
    const pending = deferred<ReturnType<typeof session>>()
    startRecordingMock.mockReturnValue(pending.promise)
    const resolved = withSetup(() => useAssistantVoice())

    resolved.startListening('zh-CN', vi.fn())
    resolved.startListening('zh-CN', vi.fn())
    expect(startRecordingMock).toHaveBeenCalledOnce()
    resolved.cancelListening()
    pending.resolve(session())
  })
})
