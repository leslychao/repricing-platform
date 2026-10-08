import { defineConfig } from '@hey-api/openapi-ts';

export default defineConfig({
  input: '../backend/app/src/main/resources/openapi.yaml',
  output: {path:'src/app/api',clean:true},
  plugins: [
    '@hey-api/typescript',
    {name:'zod',requests:true,responses:true},
  ],
});
