import { onScopeDispose, type Ref } from 'vue';

/** 每轮先清空列表；只有最新请求可以写入数据、报错或结束加载。 */
export function createTableLoader(loading: Ref<boolean>) {
  let version = 0;

  function invalidate() {
    version++;
    loading.value = false;
  }

  onScopeDispose(invalidate);

  return {
    invalidate,
    begin(reset: () => void) {
      const current = ++version;
      reset();
      loading.value = true;
      const isCurrent = () => current === version;
      return {
        isCurrent,
        finish() {
          if (isCurrent()) loading.value = false;
        }
      };
    }
  };
}
