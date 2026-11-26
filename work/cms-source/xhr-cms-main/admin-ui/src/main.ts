import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import 'element-plus/dist/index.css';
import App from './App.vue';
import router from './router';
import { createPinia } from 'pinia';
import './styles/variables.css';
import './styles/admin-layout.css';

const app = createApp(App);

app.use(createPinia());
app.use(router);
app.use(ElementPlus, { locale: zhCn });

router.isReady().then(() => {
  app.mount('#admin-vue-root');
});
