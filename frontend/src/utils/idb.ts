import type { Conversation } from '@/types/api'

/**
 * 原生 IndexedDB 封装
 * 用于游客（Guest）本地存储会话列表，以及已登录用户的离线/本地缓存
 */

const DB_NAME = 'xcweb_local_db'
const DB_VERSION = 1
const STORE_CONVERSATIONS = 'conversations'

interface StoredConversationRecord {
  storageKey: string // `${ownerKey}:${conversation.id}`
  ownerKey: string
  conversation: Conversation
  updatedAt: number
}

let dbPromise: Promise<IDBDatabase> | null = null

function openDatabase(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise

  dbPromise = new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new Error('IndexedDB is not supported in this environment'))
      return
    }

    const request = indexedDB.open(DB_NAME, DB_VERSION)

    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(STORE_CONVERSATIONS)) {
        const store = db.createObjectStore(STORE_CONVERSATIONS, { keyPath: 'storageKey' })
        store.createIndex('by_owner', 'ownerKey', { unique: false })
        store.createIndex('by_updated', 'updatedAt', { unique: false })
      }
    }

    request.onsuccess = () => resolve(request.result)
    request.onerror = () => {
      dbPromise = null
      reject(request.error ?? new Error('Failed to open IndexedDB'))
    }
  })

  return dbPromise
}

function clonePlain<T>(obj: T): T {
  return JSON.parse(JSON.stringify(obj)) as T
}

export async function idbGetConversations(ownerKey: string): Promise<Conversation[]> {
  try {
    const db = await openDatabase()
    return await new Promise<Conversation[]>((resolve, reject) => {
      const tx = db.transaction(STORE_CONVERSATIONS, 'readonly')
      const store = tx.objectStore(STORE_CONVERSATIONS)
      const index = store.index('by_owner')
      const req = index.getAll(IDBKeyRange.only(ownerKey))

      req.onsuccess = () => {
        const rows = (req.result as StoredConversationRecord[]) || []
        const list = rows
          .map((r) => r.conversation)
          .sort((a, b) => b.updatedAt - a.updatedAt)
        resolve(list)
      }
      req.onerror = () => reject(req.error)
    })
  } catch {
    try {
      const raw = localStorage.getItem(`xcweb_conv_fallback_${ownerKey}`)
      if (!raw) return []
      const parsed = JSON.parse(raw) as Conversation[]
      return parsed.sort((a, b) => b.updatedAt - a.updatedAt)
    } catch {
      return []
    }
  }
}

export async function idbSaveConversation(
  ownerKey: string,
  conversation: Conversation,
): Promise<void> {
  if (conversation.isIncognito) {
    // 无痕对话绝不落盘
    return
  }

  const plainConv = clonePlain(conversation)

  try {
    const db = await openDatabase()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_CONVERSATIONS, 'readwrite')
      const store = tx.objectStore(STORE_CONVERSATIONS)
      const record: StoredConversationRecord = {
        storageKey: `${ownerKey}:${plainConv.id}`,
        ownerKey,
        conversation: plainConv,
        updatedAt: plainConv.updatedAt,
      }
      const req = store.put(record)
      req.onsuccess = () => resolve()
      req.onerror = () => reject(req.error)
    })
  } catch {
    try {
      const existing = await idbGetConversations(ownerKey)
      const idx = existing.findIndex((c) => c.id === plainConv.id)
      if (idx >= 0) {
        existing[idx] = plainConv
      } else {
        existing.unshift(plainConv)
      }
      localStorage.setItem(`xcweb_conv_fallback_${ownerKey}`, JSON.stringify(existing))
    } catch {
      // ignore fallback error
    }
  }
}

export async function idbDeleteConversation(
  ownerKey: string,
  conversationId: string,
): Promise<void> {
  try {
    const db = await openDatabase()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_CONVERSATIONS, 'readwrite')
      const store = tx.objectStore(STORE_CONVERSATIONS)
      const req = store.delete(`${ownerKey}:${conversationId}`)
      req.onsuccess = () => resolve()
      req.onerror = () => reject(req.error)
    })
  } catch {
    try {
      const existing = await idbGetConversations(ownerKey)
      const filtered = existing.filter((c) => c.id !== conversationId)
      localStorage.setItem(`xcweb_conv_fallback_${ownerKey}`, JSON.stringify(filtered))
    } catch {
      // ignore
    }
  }
}
