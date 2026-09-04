import { expectDefined } from "@openclaw/normalization-core";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { OpenClawConfig } from "../config/types.openclaw.js";
import { createNextAcpTaskBackingDetail } from "./task-backing-authority.js";
import { configureTaskFlowRegistryRuntime } from "./task-flow-registry.store.test-support.js";
import { resetTaskFlowRegistryForTests } from "./task-flow-registry.test-support.js";
import type { TaskFlowRecord } from "./task-flow-registry.types.js";
import { updateTask } from "./task-registry-mutation.js";
import {
  deleteTaskRecordById,
  getTaskById,
  hasActiveTaskForChildSessionKey,
  listTasksForRelatedSessionKey,
  resolveTaskForLookupToken,
  listTaskRecordPage,
  resetTaskRegistryForTests,
} from "./task-registry-query.js";
import { createTaskRecord, markTaskTerminalById } from "./task-registry-record-api.js";
import { reloadTaskRegistryFromStore, tasks as authoritativeTasks } from "./task-registry-state.js";
import { configureTaskRegistryRuntime } from "./task-registry.store.js";
import type { TaskRecord } from "./task-registry.types.js";

afterEach(() => {
  resetTaskRegistryForTests({ persist: false });
  resetTaskFlowRegistryForTests({ persist: false });
});

function configureTaskSnapshot(tasks: Iterable<TaskRecord>): void {
  let snapshotTasks = new Map([...tasks].map((task) => [task.taskId, task]));
  configureTaskRegistryRuntime({
    store: {
      loadSnapshot: () => ({ tasks: snapshotTasks, deliveryStates: new Map() }),
      saveSnapshot: (snapshot) => {
        snapshotTasks = new Map(snapshot.tasks);
      },
      upsertTask: (task) => {
        snapshotTasks.set(task.taskId, task);
      },
    },
  });
}

async function readTaskPage(params: Parameters<typeof listTaskRecordPage>[0]) {
  const result = await listTaskRecordPage(params);
  expect(result.ok).toBe(true);
  if (!result.ok) {
    throw new Error(`task page failed: ${result.error}`);
  }
  return result.value;
}

const sessionScopes = [undefined, "agent:main:main"];

describe.each(sessionScopes)("listTaskRecordPage (%s)", (sessionKey) => {
  it.each([
    { count: 32, mutationTurn: 1, completes: true },
    { count: 64, mutationTurn: 2, completes: true },
    { count: 33, mutationTurn: 1, completes: false },
    { count: 65, mutationTurn: 2, completes: false },
  ])(
    "finishes complete batches but yields unfinished work ($count tasks)",
    async ({ count, mutationTurn, completes }) => {
      configureTaskSnapshot(
        Array.from({ length: count }, (_, index): TaskRecord => ({
          taskId: `task-${index}`,
          runtime: "cli",
          requesterSessionKey: "agent:main:main",
          ownerKey: "agent:main:main",
          scopeKind: "session",
          task: "Task with queued activity",
          status: "running",
          deliveryStatus: "pending",
          notifyPolicy: "done_only",
          createdAt: 1,
          lastEventAt: 1,
        })),
      );
      let turn = 0;
      let mutations = 0;
      const update = () => {
        turn += 1;
        if (turn >= mutationTurn) {
          mutations += 1;
          markTaskTerminalById({
            taskId: "task-0",
            status: "succeeded",
            endedAt: mutations + 1,
          });
        }
        pending = setImmediate(update);
      };
      let pending = setImmediate(update);
      try {
        const page = await listTaskRecordPage({ offset: 0, limit: count, sessionKey });
        if (completes) {
          expect(page.ok).toBe(true);
          expect(mutations).toBe(0);
        } else {
          expect(page).toEqual({ ok: false, error: "registry_changed" });
          expect(mutations).toBeGreaterThanOrEqual(3);
        }
      } finally {
        clearImmediate(pending);
      }
    },
  );

  it("keeps large page scans responsive and sorts only the selected window", async () => {
    const total = 10_000;
    const offset = 13;
    const limit = 7;
    const snapshotTasks = new Map<string, TaskRecord>();
    for (let index = 0; index < total; index += 1) {
      const taskId = `task-${String(index).padStart(5, "0")}`;
      const lastEventAt = Math.floor(((index * 7_919) % total) / 4);
      snapshotTasks.set(taskId, {
        taskId,
        runtime: "cli",
        requesterSessionKey: "agent:main:main",
        ownerKey: "agent:main:main",
        scopeKind: "session",
        runId: `run-${index}`,
        task: "Bounded page selection",
        status: "succeeded",
        deliveryStatus: "not_applicable",
        notifyPolicy: "done_only",
        createdAt: 0,
        startedAt: 0,
        lastEventAt,
      });
    }
    const expectedTaskIds = [...snapshotTasks.values()]
      .toSorted(
        (left, right) =>
          (right.lastEventAt ?? 0) - (left.lastEventAt ?? 0) ||
          left.taskId.localeCompare(right.taskId),
      )
      .slice(offset, offset + limit)
      .map((task) => task.taskId);
    configureTaskSnapshot(snapshotTasks.values());

    let eventLoopTurnRan = false;
    const sortedInputLengths: number[] = [];
    const originalToSorted = Array.prototype.toSorted;
    const sortSpy = vi.spyOn(Array.prototype, "toSorted").mockImplementation(function <T>(
      this: T[],
      compareFn?: (left: T, right: T) => number,
    ): T[] {
      const first = this[0];
      if (first && typeof first === "object" && "taskId" in first) {
        sortedInputLengths.push(this.length);
      }
      return Reflect.apply(originalToSorted, this, [compareFn]) as T[];
    });
    try {
      setImmediate(() => {
        eventLoopTurnRan = true;
      });
      const page = await readTaskPage({ offset, limit, sessionKey });

      expect(page.tasks.map((task) => task.taskId)).toEqual(expectedTaskIds);
      expect(page.hasMore).toBe(true);
      expect(eventLoopTurnRan).toBe(true);
      expect(Math.max(0, ...sortedInputLengths)).toBeLessThanOrEqual(offset + limit);

      sortedInputLengths.length = 0;
      const emptyPage = await readTaskPage({ offset: total + 1, limit: 1, sessionKey });
      expect(emptyPage).toMatchObject({ tasks: [], hasMore: false });
      expect(sortedInputLengths).toEqual([]);
    } finally {
      sortSpy.mockRestore();
    }
  });

  it("selects the terminal page by completion instead of later activity", async () => {
    const tasks = [
      {
        taskId: "finished-newest",
        endedAt: 300,
        lastEventAt: 100,
      },
      {
        taskId: "legacy-terminal",
        endedAt: undefined,
        lastEventAt: 250,
      },
      {
        taskId: "finished-middle",
        endedAt: 200,
        lastEventAt: 200,
      },
    ].map(({ taskId, endedAt, lastEventAt }): TaskRecord => ({
      taskId,
      runtime: "cli",
      requesterSessionKey: "agent:main:main",
      ownerKey: "agent:main:main",
      scopeKind: "session",
      task: taskId,
      status: "succeeded",
      deliveryStatus: "not_applicable",
      notifyPolicy: "done_only",
      createdAt: 0,
      endedAt,
      lastEventAt,
    }));
    configureTaskSnapshot(tasks);

    const page = await readTaskPage({ offset: 0, limit: 2, sortBy: "endedAt", sessionKey });

    expect(page.tasks.map((task) => task.taskId)).toEqual(["finished-newest", "legacy-terminal"]);
  });

  it("returns page records isolated from the registry", async () => {
    const task: TaskRecord = {
      taskId: "task-isolated",
      runtime: "cli",
      requesterSessionKey: "agent:main:main",
      ownerKey: "agent:main:main",
      scopeKind: "session",
      task: "Isolated task",
      status: "running",
      deliveryStatus: "pending",
      notifyPolicy: "done_only",
      createdAt: 1,
      detail: { nested: { value: "original" } },
    };
    configureTaskSnapshot([task]);

    const page = await readTaskPage({ offset: 0, limit: 1, sessionKey });
    const detail = page.tasks[0]?.detail as { nested: { value: string } } | undefined;
    expect(detail).toBeDefined();
    if (detail) {
      detail.nested.value = "mutated";
    }

    expect(getTaskById(task.taskId)?.detail).toEqual({ nested: { value: "original" } });
  });
});

describe("related-session selection", () => {
  it("does not use the executor as the requester owner for a legacy bare task", async () => {
    const task: TaskRecord = {
      taskId: "task-legacy-owner",
      runtime: "subagent",
      requesterSessionKey: "global",
      ownerKey: "global",
      scopeKind: "session",
      childSessionKey: "agent:research:subagent:child",
      agentId: "research",
      runId: "run-legacy-owner",
      task: "Owned by ops, executed by research",
      status: "running",
      deliveryStatus: "pending",
      notifyPolicy: "done_only",
      createdAt: 1,
    };
    const foreign = {
      ...task,
      taskId: "task-foreign-owner",
      requesterAgentId: "research",
      agentId: "ops",
    };
    configureTaskSnapshot([task, foreign]);
    const cfg = {
      session: { scope: "global", store: "/tmp/shared-sessions.sqlite" },
      agents: {
        ownership: "explicit",
        defaults: { sessionStore: { agentId: "ops" } },
        entries: { ops: {}, research: {} },
      },
    } satisfies OpenClawConfig;

    expect(
      (
        await readTaskPage({
          offset: 0,
          limit: 10,
          sessionKey: "global",
          sessionAgentId: "ops",
          cfg,
        })
      ).tasks.map((entry) => entry.taskId),
    ).toEqual([task.taskId]);
    expect(
      (
        await readTaskPage({
          offset: 0,
          limit: 10,
          sessionKey: "global",
          sessionAgentId: "research",
          cfg,
        })
      ).tasks.map((entry) => entry.taskId),
    ).toEqual([foreign.taskId]);
  });

  it("keeps missing indexed IDs bounded across a yielded registry replacement", async () => {
    const sessionKey = "agent:main:reset";
    const records = Array.from({ length: 97 }, (_, index): TaskRecord => ({
      taskId: `task-${index}`,
      runtime: "cli",
      requesterSessionKey: sessionKey,
      ownerKey: sessionKey,
      scopeKind: "session",
      task: "Before replacement",
      status: "running",
      deliveryStatus: "not_applicable",
      notifyPolicy: "silent",
      createdAt: 1,
    }));
    configureTaskSnapshot(records);
    getTaskById("task-0");
    let reads = 0;
    const readsPerTurn: number[] = [];
    const get = authoritativeTasks.get.bind(authoritativeTasks);
    const spy = vi.spyOn(authoritativeTasks, "get").mockImplementation((id) => {
      reads += 1;
      return get(id);
    });
    let replaced = false;
    const preparedAfterReplacement: string[] = [];
    const tick = () => {
      readsPerTurn.push(reads);
      reads = 0;
      if (!replaced) {
        configureTaskSnapshot([
          { ...expectDefined(records[0], "replacement fixture"), taskId: "replacement" },
        ]);
        reloadTaskRegistryFromStore();
        replaced = true;
      }
      pending = setImmediate(tick);
    };
    let pending = setImmediate(tick);
    try {
      const page = await readTaskPage({
        offset: 0,
        limit: 10,
        sessionKey,
        prepareFilter: (batch) => {
          if (replaced) {
            preparedAfterReplacement.push(...batch.map((task) => task.taskId));
          }
          return () => true;
        },
      });
      readsPerTurn.push(reads);
      expect(page.tasks.map((task) => task.taskId)).toEqual(["replacement"]);
      expect(preparedAfterReplacement).toEqual(["replacement"]);
      expect(Math.max(...readsPerTurn)).toBeLessThanOrEqual(32);
    } finally {
      clearImmediate(pending);
      spy.mockRestore();
    }
  });

  it("keeps requester-only matches out of existing ACP generation history", () => {
    const sessionKey = "agent:main:child";
    const flow: TaskFlowRecord = {
      flowId: "mirrored",
      syncMode: "task_mirrored",
      ownerKey: "agent:main:owner",
      revision: 0,
      status: "running",
      notifyPolicy: "silent",
      goal: "Generation history",
      createdAt: 1,
      updatedAt: 1,
    };
    const records = [
      {
        taskId: "child",
        ownerKey: "agent:main:owner",
        requesterSessionKey: "agent:main:requester",
        childSessionKey: sessionKey,
        generation: 2,
      },
      {
        taskId: "owner",
        ownerKey: sessionKey,
        requesterSessionKey: "agent:main:requester",
        childSessionKey: "agent:main:other",
        generation: 3,
      },
      {
        taskId: "requester",
        ownerKey: "agent:main:owner",
        requesterSessionKey: sessionKey,
        childSessionKey: "agent:main:other",
        generation: 99,
      },
    ].map(({ generation, ...keys }): TaskRecord => ({
      taskId: keys.taskId,
      ownerKey: keys.ownerKey,
      requesterSessionKey: keys.requesterSessionKey,
      childSessionKey: keys.childSessionKey,
      runtime: "acp",
      scopeKind: "session",
      parentFlowId: `mirrored-${keys.taskId}`,
      task: "Generation history",
      status: "running",
      deliveryStatus: "not_applicable",
      notifyPolicy: "silent",
      createdAt: 1,
      detail: {
        kind: "task_backing_instance",
        runtime: "acp",
        instanceId: keys.taskId,
        generation,
      },
    }));
    configureTaskFlowRegistryRuntime({
      store: {
        loadSnapshot: () => ({
          flows: new Map(
            records.map((task) => {
              const flowId = expectDefined(task.parentFlowId, "mirrored task flow");
              return [flowId, { ...flow, flowId, ownerKey: task.ownerKey }];
            }),
          ),
        }),
        saveSnapshot: () => {},
      },
    });
    configureTaskSnapshot(records);
    expect(
      createNextAcpTaskBackingDetail({ childSessionKey: sessionKey, instanceId: "next" }),
    ).toEqual({ kind: "task_backing_instance", runtime: "acp", instanceId: "next", generation: 4 });
  });

  it("selects twenty stable tasks before foreign ledger mutations can force a yield", async () => {
    const targetKey = "agent:main:target";
    const records = Array.from({ length: 33 }, (_, index): TaskRecord => ({
      taskId: `task-${index}`,
      runtime: "cli",
      requesterSessionKey: index < 20 ? targetKey : "agent:main:foreign",
      ownerKey: index < 20 ? targetKey : "agent:main:foreign",
      scopeKind: "session",
      task: "Stable scoped page",
      status: "running",
      deliveryStatus: "not_applicable",
      notifyPolicy: "silent",
      createdAt: 1,
    }));
    configureTaskSnapshot(records);
    const before = records.slice(0, 20).map((task) => getTaskById(task.taskId));
    let mutations = 0;
    const mutate = () => {
      if (mutations === 9) {
        return;
      }
      expect(
        markTaskTerminalById({
          taskId: `task-${20 + mutations++}`,
          status: "succeeded",
          endedAt: 100 + mutations,
        }),
      ).not.toBeNull();
      pending = setImmediate(mutate);
    };
    let pending = setImmediate(mutate);
    try {
      const page = await listTaskRecordPage({ offset: 0, limit: 100, sessionKey: targetKey });
      expect({ page, mutations }).toMatchObject({ page: { ok: true }, mutations: 0 });
      if (page.ok) {
        expect(page.value.tasks).toHaveLength(20);
        expect(page.value.hasMore).toBe(false);
      }
      expect(records.slice(0, 20).map((task) => getTaskById(task.taskId))).toEqual(before);
    } finally {
      clearImmediate(pending);
    }
  });

  it.each(["requesterSessionKey", "ownerKey", "childSessionKey", "all"] as const)(
    "maintains %s matches across creation, replacement, removal, and restore",
    async (field) => {
      configureTaskSnapshot([]);
      const key = "global";
      const keys =
        field === "all"
          ? { requesterSessionKey: key, ownerKey: key, childSessionKey: key }
          : { [field]: ` ${key} ` };
      const task = createTaskRecord({
        runtime: "cli",
        requesterSessionKey: "agent:ops:requester",
        ownerKey: "agent:ops:owner",
        childSessionKey: "agent:ops:child",
        requesterAgentId: "ops",
        agentId: "ops",
        scopeKind: "session",
        task: "Indexed relationship",
        status: "running",
        deliveryStatus: "not_applicable",
        ...keys,
      });
      expect(task).not.toBeNull();
      if (!task) {
        return;
      }
      const expectMatches = async (sessionKey: string, ids: string[]) => {
        const page = await readTaskPage({
          offset: 0,
          limit: 10,
          sessionKey,
          sessionAgentId: "ops",
        });
        expect(page.tasks.map((entry) => entry.taskId)).toEqual(ids);
        expect(page.hasMore).toBe(false);
        expect(
          listTasksForRelatedSessionKey(sessionKey, "ops").map((entry) => entry.taskId),
        ).toEqual(ids);
        expect(listTasksForRelatedSessionKey(sessionKey, "research")).toEqual([]);
        expect(
          (await readTaskPage({ offset: 0, limit: 10, sessionKey, sessionAgentId: "research" }))
            .tasks,
        ).toEqual([]);
        expect(resolveTaskForLookupToken(sessionKey)?.taskId).toBe(ids[0]);
      };
      await expectMatches(key, [task.taskId]);
      expect(hasActiveTaskForChildSessionKey({ sessionKey: key, agentId: "ops" })).toBe(
        field === "childSessionKey" || field === "all",
      );
      expect(hasActiveTaskForChildSessionKey({ sessionKey: key, agentId: "research" })).toBe(false);
      expect(
        hasActiveTaskForChildSessionKey({
          sessionKey: key,
          agentId: "ops",
          excludeTaskId: task.taskId,
        }),
      ).toBe(false);
      const nextKey = "moved";
      const patch =
        field === "all"
          ? { requesterSessionKey: nextKey, ownerKey: nextKey, childSessionKey: nextKey }
          : { [field]: nextKey };
      expect(updateTask(task.taskId, patch)).not.toBeNull();
      await expectMatches(key, []);
      await expectMatches(nextKey, [task.taskId]);
      reloadTaskRegistryFromStore();
      await expectMatches(key, []);
      await expectMatches(nextKey, [task.taskId]);
      expect(deleteTaskRecordById(task.taskId)).toBe(true);
      await expectMatches(nextKey, []);
      reloadTaskRegistryFromStore();
      await expectMatches(nextKey, []);
    },
  );
});
