import type { EditorCore } from "../index";

type SaveManagerOptions = {
  debounceMs?: number;
};

export class SaveManager {
  private debounceMs: number;
  private isPaused = false;
  private isSaving = false;
  private hasPendingSave = false;
  private currentSave: Promise<void> | null = null;
  private saveTimer: ReturnType<typeof setTimeout> | null = null;
  private unsubscribeHandlers: Array<() => void> = [];

  constructor({
    editor,
    debounceMs = 800,
  }: {
    editor: EditorCore;
  } & SaveManagerOptions) {
    this.editor = editor;
    this.debounceMs = debounceMs;
  }

  private editor: EditorCore;

  start(): void {
    if (this.unsubscribeHandlers.length > 0) return;

    this.unsubscribeHandlers = [
      this.editor.media.subscribe(() => {
        this.markDirty();
      }),
      this.editor.scenes.subscribe(() => {
        this.markDirty();
      }),
      this.editor.timeline.subscribe(() => {
        this.markDirty();
      }),
    ];
  }

  stop(): void {
    for (const unsubscribe of this.unsubscribeHandlers) {
      unsubscribe();
    }
    this.unsubscribeHandlers = [];
    this.clearTimer();
  }

  pause(): void {
    this.isPaused = true;
  }

  resume(): void {
    this.isPaused = false;
    if (this.hasPendingSave) {
      this.queueSave();
    }
  }

  markDirty({ force = false }: { force?: boolean } = {}): void {
    if (this.isPaused && !force) return;
    this.hasPendingSave = true;
    this.queueSave();
  }

  async flush(): Promise<void> {
    this.hasPendingSave = true;
    await this.saveNow();
    if (this.hasPendingSave) await this.saveNow();
    if (this.getIsDirty()) throw new Error("Changes are not saved yet. Check the local server and try again.");
  }

  getIsDirty(): boolean {
    return this.hasPendingSave || this.isSaving;
  }

  private queueSave(): void {
    if (this.isSaving) return;
    if (this.saveTimer) {
      clearTimeout(this.saveTimer);
    }
    this.saveTimer = setTimeout(() => {
      void this.saveNow();
    }, this.debounceMs);
  }

  private async saveNow(): Promise<void> {
    if (this.currentSave) return this.currentSave;
    const pending = this.performSave();
    this.currentSave = pending;
    try { await pending; } finally { this.currentSave = null; }
  }

  private async performSave(): Promise<void> {
    if (this.isSaving) return;
    if (!this.hasPendingSave) return;

    const activeProject = this.editor.project.getActive();
    if (!activeProject) return;
    if (this.editor.project.getIsLoading()) return;
    if (this.editor.project.getMigrationState().isMigrating) return;

    this.isSaving = true;
    this.hasPendingSave = false;
    this.clearTimer();

    try {
      await this.editor.project.saveCurrentProject();
    } catch (error) {
      this.hasPendingSave = true;
      this.editor.adapters.toast.error("Project could not be saved", {description: error instanceof Error ? error.message : "Check local server and storage."});
    } finally {
      this.isSaving = false;
      if (this.hasPendingSave) {
        this.queueSave();
      }
    }
  }

  private clearTimer(): void {
    if (!this.saveTimer) return;
    clearTimeout(this.saveTimer);
    this.saveTimer = null;
  }
}
