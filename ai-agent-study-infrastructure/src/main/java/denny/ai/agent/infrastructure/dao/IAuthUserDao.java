package denny.ai.agent.infrastructure.dao;

import denny.ai.agent.infrastructure.dao.po.AuthUserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IAuthUserDao {

    AuthUserPO queryByAccount(@Param("account") String account);

    AuthUserPO queryByUserId(@Param("userId") String userId);

    int insert(AuthUserPO user);
}
