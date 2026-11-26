package com.xinhongru.mapper;

import com.xinhongru.entity.ContentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MemberCredentialMapper {
    ContentEntity lockMember(@Param("username") String username, @Param("classId") int classId);

    String findHash(@Param("id") int id);

    int saveHash(@Param("id") int id, @Param("hash") String hash);

    int clearLegacy(@Param("id") int id, @Param("classId") int classId);

    int recordLogin(@Param("id") int id, @Param("classId") int classId,
                    @Param("visits") String visits, @Param("ip") String ip);
}
